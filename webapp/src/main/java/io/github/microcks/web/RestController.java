/*
 * Copyright The Microcks Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.microcks.web;

import io.github.microcks.domain.Header;
import io.github.microcks.domain.Operation;
import io.github.microcks.domain.ParameterConstraint;
import io.github.microcks.domain.ParameterLocation;
import io.github.microcks.domain.Response;
import io.github.microcks.domain.Service;
import io.github.microcks.repository.ResponseRepository;
import io.github.microcks.repository.ServiceRepository;
import io.github.microcks.repository.ServiceStateRepository;
import io.github.microcks.service.ProxyService;
import io.github.microcks.util.*;
import io.github.microcks.util.dispatcher.FallbackSpecification;
import io.github.microcks.util.dispatcher.JsonEvaluationSpecification;
import io.github.microcks.util.dispatcher.JsonExpressionEvaluator;
import io.github.microcks.util.dispatcher.JsonMappingException;
import io.github.microcks.util.dispatcher.ProxyFallbackSpecification;
import io.github.microcks.util.el.EvaluableRequest;
import io.github.microcks.util.script.ScriptEngineBinder;
import io.github.microcks.service.ServiceStateStore;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriUtils;

import jakarta.servlet.http.HttpServletRequest;

import javax.annotation.CheckForNull;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * A controller for mocking Rest responses.
 * @author laurent
 */
@org.springframework.web.bind.annotation.RestController
@RequestMapping("/rest")
public class RestController {

   /** A simple logger for diagnostic messages. */
   private static final Logger log = LoggerFactory.getLogger(RestController.class);

   private final ServiceRepository serviceRepository;
   private final ServiceStateRepository serviceStateRepository;
   private final ResponseRepository responseRepository;

   private final ApplicationContext applicationContext;

   private final ProxyService proxyService;

   @Value("${mocks.enable-invocation-stats}")
   private Boolean enableInvocationStats;
   @Value("${mocks.rest.enable-cors-policy}")
   private Boolean enableCorsPolicy;
   @Value("${mocks.rest.cors.allowedOrigins}")
   private String corsAllowedOrigins;
   @Value("${mocks.rest.cors.allowCredentials}")
   private Boolean corsAllowCredentials;

   /**
    * Build a RestController with required dependencies.
    * @param serviceRepository      The repository to access services definitions
    * @param serviceStateRepository The repository to access service state
    * @param responseRepository     The repository to access responses definitions
    * @param applicationContext     The Spring application context
    * @param proxyService           The proxy to external URLs or services
    */
   public RestController(ServiceRepository serviceRepository, ServiceStateRepository serviceStateRepository,
         ResponseRepository responseRepository, ApplicationContext applicationContext, ProxyService proxyService) {
      this.serviceRepository = serviceRepository;
      this.serviceStateRepository = serviceStateRepository;
      this.responseRepository = responseRepository;
      this.applicationContext = applicationContext;
      this.proxyService = proxyService;
   }


   @RequestMapping(value = "/{service}/{version}/**", method = { RequestMethod.HEAD, RequestMethod.OPTIONS,
         RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE })
   @WithSpan
   public ResponseEntity<byte[]> execute(@PathVariable("service") String serviceName,
         @PathVariable("version") String version, @RequestParam(value = "delay", required = false) Long delay,
         @RequestBody(required = false) String body, @RequestHeader HttpHeaders headers, HttpServletRequest request,
         HttpMethod method) {

      log.info("Servicing mock response for service [{}, {}] on uri {} with verb {}", serviceName, version,
            request.getRequestURI(), method);
      log.debug("Request body: {}", body);

      long startTime = System.currentTimeMillis();

      // Extract resourcePath for matching with correct operation and build the encoded URI fragment to retrieve simple resourcePath.
      String serviceAndVersion = MockControllerCommons.composeServiceAndVersion(serviceName, version);
      String resourcePath = MockControllerCommons.extractResourcePath(request, serviceAndVersion);
      //resourcePath = UriUtils.decode(resourcePath, "UTF-8");
      log.debug("Found resourcePath: {}", resourcePath);

      // If serviceName was encoded with '+' instead of '%20', remove them.
      if (serviceName.contains("+")) {
         serviceName = serviceName.replace('+', ' ');
      }

      // Find matching service.
      Service service = serviceRepository.findByNameAndVersion(serviceName, version);
      if (service == null) {
         return new ResponseEntity<>(
               String.format("The service %s with version %s does not exist!", serviceName, version).getBytes(),
               HttpStatus.NOT_FOUND);
      }

      // Find matching operation.
      Operation operation = findOperation(service, method, resourcePath);
      if (operation == null) {
         // Handle OPTIONS request if CORS policy is enabled.
         if (Boolean.TRUE.equals(enableCorsPolicy) && HttpMethod.OPTIONS.equals(method)) {
            log.debug("No valid operation found but Microcks configured to apply CORS policy");
            return handleCorsRequest(request);
         }

         log.debug("No valid operation found and Microcks configured to not apply CORS policy...");
         return new ResponseEntity<>(HttpStatus.NOT_FOUND);
      }

      log.debug("Found a valid operation {} with rules: {}", operation.getName(), operation.getDispatcherRules());
      String violationMsg = validateParameterConstraintsIfAny(operation, request);
      if (violationMsg != null) {
         return new ResponseEntity<>((violationMsg + ". Check parameter constraints.").getBytes(),
               HttpStatus.BAD_REQUEST);
      }

      // We must find dispatcher and its rules. Default to operation ones but
      // if we have a Fallback or Proxy-Fallback this is the one who is holding the first pass rules.
      String dispatcher = operation.getDispatcher();
      String dispatcherRules = operation.getDispatcherRules();
      FallbackSpecification fallback = MockControllerCommons.getFallbackIfAny(operation);
      if (fallback != null) {
         dispatcher = fallback.getDispatcher();
         dispatcherRules = fallback.getDispatcherRules();
      }
      ProxyFallbackSpecification proxyFallback = MockControllerCommons.getProxyFallbackIfAny(operation);
      if (proxyFallback != null) {
         dispatcher = proxyFallback.getDispatcher();
         dispatcherRules = proxyFallback.getDispatcherRules();
      }

      //
      DispatchContext dispatchContext = computeDispatchCriteria(service, dispatcher, dispatcherRules,
            getURIPattern(operation.getName()), UriUtils.decode(resourcePath, "UTF-8"), request, body);
      log.debug("Dispatch criteria for finding response is {}", dispatchContext.dispatchCriteria());

      Response response = null;

      // Filter depending on requested media type.
      // TODO: validate dispatchCriteria with dispatcherRules
      List<Response> responses = responseRepository.findByOperationIdAndDispatchCriteria(
            IdBuilder.buildOperationId(service, operation), dispatchContext.dispatchCriteria());
      response = getResponseByMediaType(responses, request);

      if (response == null) {
         // When using the SCRIPT or JSON_BODY dispatchers, return of evaluation may be the name of response.
         responses = responseRepository.findByOperationIdAndName(IdBuilder.buildOperationId(service, operation),
               dispatchContext.dispatchCriteria());
         response = getResponseByMediaType(responses, request);
      }

      if (response == null && fallback != null) {
         // If we've found nothing and got a fallback, that's the moment!
         responses = responseRepository.findByOperationIdAndName(IdBuilder.buildOperationId(service, operation),
               fallback.getFallback());
         response = getResponseByMediaType(responses, request);
      }

      Optional<URI> proxyUrl = MockControllerCommons.getProxyUrlIfProxyIsNeeded(dispatcher, dispatcherRules,
            resourcePath, proxyFallback, request, response);
      if (proxyUrl.isPresent()) {
         // If we've got a proxyUrl, that's the moment!
         return proxyService.callExternal(proxyUrl.get(), method, headers, body);
      }

      if (response == null) {
         if (dispatcher == null) {
            // In case no response found because dispatcher is null, just get one for the operation.
            // This will allow also OPTIONS operations (like pre-flight requests) with no dispatch criteria to work.
            log.debug("No responses found so far, tempting with just bare operationId...");
            responses = responseRepository.findByOperationId(IdBuilder.buildOperationId(service, operation));
            if (!responses.isEmpty()) {
               response = getResponseByMediaType(responses, request);
            }
         } else {
            // There is a dispatcher but we found no response => return 400 as per #819 and #1132.
            return new ResponseEntity<>(
                  String.format("The response %s does not exist!", dispatchContext.dispatchCriteria()).getBytes(),
                  HttpStatus.BAD_REQUEST);
         }
      }

      if (response != null) {
         HttpStatus status = (response.getStatus() != null ? HttpStatus.valueOf(Integer.parseInt(response.getStatus()))
               : HttpStatus.OK);

         // Deal with specific headers (content-type and redirect directive).
         HttpHeaders responseHeaders = new HttpHeaders();
         if (response.getMediaType() != null) {
            responseHeaders.setContentType(MediaType.valueOf(response.getMediaType() + ";charset=UTF-8"));
         }

         // Deal with headers from parameter constraints if any?
         recopyHeadersFromParameterConstraints(operation, request, responseHeaders);

         // Adding other generic headers (caching directives and so on...)
         if (response.getHeaders() != null) {
            // First check if they should be rendered.
            EvaluableRequest evaluableRequest = MockControllerCommons.buildEvaluableRequest(body, resourcePath,
                  request);
            Set<Header> renderedHeaders = MockControllerCommons.renderResponseHeaders(evaluableRequest,
                  dispatchContext.requestContext(), response);

            for (Header renderedHeader : renderedHeaders) {
               if ("Location".equals(renderedHeader.getName())) {
                  String location = renderedHeader.getValues().iterator().next();
                  if (!AbsoluteUrlMatcher.matches(location)) {
                     // We should process location in order to make relative URI specified an absolute one from
                     // the client perspective.
                     location = request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort()
                           + request.getContextPath() + "/rest" + serviceAndVersion + location;
                  }
                  responseHeaders.add(renderedHeader.getName(), location);
               } else {
                  if (!HttpHeaders.TRANSFER_ENCODING.equalsIgnoreCase(renderedHeader.getName())) {
                     responseHeaders.put(renderedHeader.getName(), new ArrayList<>(renderedHeader.getValues()));
                  }
               }
            }
         }

         // Render response content before waiting and returning.
         String responseContent = MockControllerCommons.renderResponseContent(body, resourcePath, request,
               dispatchContext.requestContext(), response);

         // Setting delay to default one if not set.
         if (delay == null && operation.getDefaultDelay() != null) {
            delay = operation.getDefaultDelay();
         }
         MockControllerCommons.waitForDelay(startTime, delay);

         // Publish an invocation event before returning if enabled.
         if (Boolean.TRUE.equals(enableInvocationStats)) {
            String id = MockControllerCommons.extractId(body, resourcePath, request, operation.getIdPath());
            Span.current().setAttribute("requestId", id);
            MockControllerCommons.publishMockInvocation(applicationContext, this, service, response, startTime, id);
         }

         // Return response content or just headers.
         if (responseContent != null) {
            return new ResponseEntity<>(responseContent.getBytes(StandardCharsets.UTF_8), responseHeaders, status);
         }
         return new ResponseEntity<>(responseHeaders, status);
      }

      return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
   }


   @CheckForNull
   private Operation findOperation(Service service, HttpMethod method, String resourcePath) {
      Operation result = null;

      // Remove trailing '/' if any.
      String trimmedResourcePath = resourcePath;
      if (trimmedResourcePath.endsWith("/")) {
         trimmedResourcePath = resourcePath.substring(0, resourcePath.length() - 1);
      }

      for (Operation operation : service.getOperations()) {
         // Select operation based onto Http verb (GET, POST, PUT, etc ...)
         if (operation.getMethod().equals(method.name())) {
            // ... then check is we have a matching resource path.
            if (operation.getResourcePaths() != null && (operation.getResourcePaths().contains(resourcePath)
                  || operation.getResourcePaths().contains(trimmedResourcePath))) {
               result = operation;
               break;
            }
         }
      }

      // We may not have found an Operation because of not exact resource path matching with an operation
      // using a Fallback dispatcher. Try again, just considering the verb and path pattern of operation.
      if (result == null) {
         for (Operation operation : service.getOperations()) {
            // Select operation based onto Http verb (GET, POST, PUT, etc ...)
            if (operation.getMethod().equals(method.name())) {
               // ... then check is current resource path matches operation path pattern.
               if (operation.getResourcePaths() != null) {
                  // Produce a matching regexp removing {part} and :part from pattern.
                  String operationPattern = getURIPattern(operation.getName());
                  //operationPattern = operationPattern.replaceAll("\\{.+\\}", "([^/])+");
                  operationPattern = operationPattern.replaceAll("\\{[\\w-]+\\}", "([^/])+");
                  operationPattern = operationPattern.replaceAll("(/:[^:^/]+)", "\\/([^/]+)");
                  if (resourcePath.matches(operationPattern)) {
                     result = operation;
                     break;
                  }
               }
            }
         }
      }
      return result;
   }

   /** Validate the parameter constraints and return a single string with violation message if any. */
   private String validateParameterConstraintsIfAny(Operation rOperation, HttpServletRequest request) {
      if (rOperation.getParameterConstraints() != null) {
         for (ParameterConstraint constraint : rOperation.getParameterConstraints()) {
            String violationMsg = ParameterConstraintUtil.validateConstraint(request, constraint);
            if (violationMsg != null) {
               return violationMsg;
            }
         }
      }
      return null;
   }

   /** Compute a dispatch context with a dispatchCriteria string from type, rules and request elements. */
   private DispatchContext computeDispatchCriteria(Service service, String dispatcher, String dispatcherRules,
         String uriPattern, String resourcePath, HttpServletRequest request, String body) {
      String dispatchCriteria = null;
      Map<String, Object> requestContext = null;

      // Depending on dispatcher, evaluate request with rules.
      if (dispatcher != null) {
         switch (dispatcher) {
            case DispatchStyles.SEQUENCE:
               dispatchCriteria = DispatchCriteriaHelper.extractFromURIPattern(dispatcherRules, uriPattern,
                     resourcePath);
               break;
            case DispatchStyles.SCRIPT:
               ScriptEngineManager sem = new ScriptEngineManager();
               requestContext = new HashMap<>();
               try {
                  // Evaluating request with script coming from operation dispatcher rules.
                  ScriptEngine se = sem.getEngineByExtension("groovy");
                  ScriptEngineBinder.bindEnvironment(se, body, requestContext,
                        new ServiceStateStore(serviceStateRepository, service.getId()), request);
                  String script = ScriptEngineBinder.ensureSoapUICompatibility(dispatcherRules);
                  dispatchCriteria = (String) se.eval(script);
               } catch (Exception e) {
                  log.error("Error during Script evaluation", e);
               }
               break;
            case DispatchStyles.URI_PARAMS:
               String fullURI = request.getRequestURL() + "?" + request.getQueryString();
               dispatchCriteria = DispatchCriteriaHelper.extractFromURIParams(dispatcherRules, fullURI);
               break;
            case DispatchStyles.URI_PARTS:
               // /tenantId?t1/userId=x
               dispatchCriteria = DispatchCriteriaHelper.extractFromURIPattern(dispatcherRules, uriPattern,
                     resourcePath);
               break;
            case DispatchStyles.URI_ELEMENTS:
               dispatchCriteria = DispatchCriteriaHelper.extractFromURIPattern(dispatcherRules, uriPattern,
                     resourcePath);
               fullURI = request.getRequestURL() + "?" + request.getQueryString();
               dispatchCriteria += DispatchCriteriaHelper.extractFromURIParams(dispatcherRules, fullURI);
               break;
            case DispatchStyles.JSON_BODY:
               try {
                  JsonEvaluationSpecification specification = JsonEvaluationSpecification
                        .buildFromJsonString(dispatcherRules);
                  dispatchCriteria = JsonExpressionEvaluator.evaluate(body, specification);
               } catch (JsonMappingException jme) {
                  log.error("Dispatching rules of operation cannot be interpreted as JsonEvaluationSpecification", jme);
               }
               break;
         }
      }

      return new DispatchContext(dispatchCriteria, requestContext);
   }

   /** Recopy headers defined with parameter constraints. */
   private void recopyHeadersFromParameterConstraints(Operation rOperation, HttpServletRequest request,
         HttpHeaders responseHeaders) {
      if (rOperation.getParameterConstraints() != null) {
         for (ParameterConstraint constraint : rOperation.getParameterConstraints()) {
            if (ParameterLocation.header == constraint.getIn() && constraint.isRecopy()) {
               String value = request.getHeader(constraint.getName());
               if (value != null) {
                  responseHeaders.set(constraint.getName(), value);
               }
            }
         }
      }
   }

   /**
    * Filter responses using the Accept header for content-type, default to the first. Return null if no responses to
    * filter.
    */
   private Response getResponseByMediaType(List<Response> responses, HttpServletRequest request) {
      if (!responses.isEmpty()) {
         String accept = request.getHeader("Accept");
         return responses.stream().filter(r -> !StringUtils.isNotEmpty(accept) || accept.equals(r.getMediaType()))
               .findFirst().orElse(responses.get(0));
      }
      return null;
   }

   /** Retrieve URI Pattern from operation name (remove starting verb name). */
   private String getURIPattern(String operationName) {
      if (operationName.startsWith("GET ") || operationName.startsWith("POST ") || operationName.startsWith("PUT ")
            || operationName.startsWith("DELETE ") || operationName.startsWith("PATCH ")
            || operationName.startsWith("OPTIONS ")) {
         return operationName.substring(operationName.indexOf(' ') + 1);
      }
      return operationName;
   }

   /** Handle a CORS request putting the correct headers in response entity. */
   private ResponseEntity<byte[]> handleCorsRequest(HttpServletRequest request) {
      // Retrieve and set access control headers from those coming in request.
      List<String> accessControlHeaders = new ArrayList<>();
      Collections.list(request.getHeaders("Access-Control-Request-Headers")).forEach(accessControlHeaders::add);
      HttpHeaders requestHeaders = new HttpHeaders();
      requestHeaders.setAccessControlAllowHeaders(accessControlHeaders);
      requestHeaders.setAccessControlExposeHeaders(accessControlHeaders);

      // Apply CORS headers to response with 204 response code.
      return ResponseEntity.noContent().header("Access-Control-Allow-Origin", corsAllowedOrigins)
            .header("Access-Control-Allow-Methods", "POST, PUT, GET, OPTIONS, DELETE, PATCH").headers(requestHeaders)
            .header("Access-Allow-Credentials", String.valueOf(corsAllowCredentials))
            .header("Access-Control-Max-Age", "3600").header("Vary", "Accept-Encoding, Origin").build();
   }
}
