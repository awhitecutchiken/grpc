/*
 * Copyright 2020 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.xds.internal.rbac.engine;

import com.google.api.expr.v1alpha1.Expr;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Descriptors.Descriptor;
import io.envoyproxy.envoy.config.rbac.v2.Policy;
import io.envoyproxy.envoy.config.rbac.v2.RBAC;
import io.envoyproxy.envoy.config.rbac.v2.RBAC.Action;
import io.grpc.xds.internal.rbac.engine.cel.Activation;
import io.grpc.xds.internal.rbac.engine.cel.DefaultDispatcher;
import io.grpc.xds.internal.rbac.engine.cel.DefaultInterpreter;
import io.grpc.xds.internal.rbac.engine.cel.DescriptorMessageProvider;
import io.grpc.xds.internal.rbac.engine.cel.Dispatcher;
import io.grpc.xds.internal.rbac.engine.cel.IncompleteData;
import io.grpc.xds.internal.rbac.engine.cel.Interpretable;
import io.grpc.xds.internal.rbac.engine.cel.Interpreter;
import io.grpc.xds.internal.rbac.engine.cel.InterpreterException;
import io.grpc.xds.internal.rbac.engine.cel.RuntimeTypeProvider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * CEL Evaluation Engine is part of the authorization framework in gRPC.
 * CEL Evaluation Engine takes one or two Envoy RBAC policies as input
 * and uses CEL library to evaluate the condition field 
 * inside each RBAC policy based on the provided Envoy Attributes. 
 * CEL Evaluation Engine will generate an authorization decision which
 * could be ALLOW, DENY or UNKNOWN.
 * 
 * <p>Use as in:
 * 
 * <pre>
 *  AuthorizationEngine engine = new AuthorizationEngine(
 *      ImmutableList.of(rbacPolicies));
 *  AuthorizationDecision result = engine.evaluate(new EvaluateArgs(call, headers));
 * </pre>
 */
public class AuthorizationEngine {
  private static final Logger log = Logger.getLogger(AuthorizationEngine.class.getName());
  
  /**
   * RbacEngine is an inner class that holds RBAC action 
   * and conditions of RBAC policy.
   */
  private static class RbacEngine {
    @SuppressWarnings("UnusedVariable")
    private final Action action;
    private final ImmutableMap<String, Expr> conditions;

    public RbacEngine(Action action, ImmutableMap<String, Expr> conditions) {
      this.action = action;
      this.conditions = conditions;
    }
  }

  private final RbacEngine allowEngine;
  private final RbacEngine denyEngine;

  /**
   * Creates a CEL-based Authorization Engine from a list of Envoy RBACs.
   * The constructor can take either one or two Envoy RBACs.
   * When it takes two RBACs, the order has to be a RBAC with DENY action
   * followed by a RBAC with ALLOW action.
   * @param rbacPolicies input Envoy RBAC list.
   * @throws IllegalArgumentException if the user inputs an invalid RBAC list.
   */
  public AuthorizationEngine(ImmutableList<RBAC> rbacPolicies) throws IllegalArgumentException {
    if (rbacPolicies.size() < 1 || rbacPolicies.size() > 2) {
      throw new IllegalArgumentException(
        "Invalid RBAC list size, must provide either one RBAC or two RBACs. ");
    } 
    if (rbacPolicies.size() == 2 && (rbacPolicies.get(0).getAction() != Action.DENY 
        || rbacPolicies.get(1).getAction() != Action.ALLOW)) {
      throw new IllegalArgumentException( "Invalid RBAC list, " 
          + "must provide a RBAC with DENY action followed by a RBAC with ALLOW action. ");
    }
    RbacEngine allowEngine = null;
    RbacEngine denyEngine = null;
    for (RBAC rbac : rbacPolicies) {
      Map<String, Expr> conditions = new LinkedHashMap<>();
      for (Map.Entry<String, Policy> rbacPolicy: rbac.getPolicies().entrySet()) {
        conditions.put(rbacPolicy.getKey(), rbacPolicy.getValue().getCondition());
      }
      if (rbac.getAction() == Action.ALLOW) {
        allowEngine = new RbacEngine(Action.ALLOW, ImmutableMap.copyOf(conditions));
      }
      if (rbac.getAction() == Action.DENY) {
        denyEngine = new RbacEngine(Action.DENY, ImmutableMap.copyOf(conditions));
      }
    }
    this.allowEngine = allowEngine;
    this.denyEngine = denyEngine;
  }

  /**
   * The evaluate function performs the core authorization mechanism of CEL Evaluation Engine.
   * Determines whether a gRPC call is allowed, denied, or unable to decide.
   * @param args evaluate argument that is used to evaluate the RBAC conditions.
   * @return an AuthorizationDecision generated by CEL Evaluation Engine.
   */
  public AuthorizationDecision evaluate(EvaluateArgs args) {
    List<String> unknownPolicyNames = new ArrayList<>();
    // Set up activation used in CEL library's eval function.
    Activation activation = Activation.copyOf(args.generateEnvoyAttributes());
    // Iterate through denyEngine's map.
    // If there is match, immediately return deny. 
    // If there are unknown results, return undecided. 
    // If all non-match, then iterate through allowEngine.
    if (denyEngine != null) {
      AuthorizationDecision authzDecision = evaluateEngine(denyEngine.conditions.entrySet(), 
          AuthorizationDecision.Decision.DENY, unknownPolicyNames, activation);
      if (authzDecision != null) {
        return authzDecision;
      }
    }
    // Once we enter allowEngine, if there is a match, immediately return allow. 
    // In the end of iteration, if there are unknown rules, return undecided.
    // If all non-match, return deny.
    if (allowEngine != null) {
      AuthorizationDecision authzDecision = evaluateEngine(allowEngine.conditions.entrySet(), 
          AuthorizationDecision.Decision.ALLOW, unknownPolicyNames, activation);
      if (authzDecision != null) {
        return authzDecision;
      }
    }
    // Only has a denyEngine and it’s unmatched.
    if (this.allowEngine == null && this.denyEngine != null) {
      return new AuthorizationDecision(
          AuthorizationDecision.Decision.ALLOW, new ArrayList<String>());
    }
    // None of denyEngine and allowEngine matched, or the single Allow Engine is unmatched.
    return new AuthorizationDecision(AuthorizationDecision.Decision.DENY, new ArrayList<String>());
  }

  /** Evaluate a single RbacEngine. */
  protected AuthorizationDecision evaluateEngine(Set<Map.Entry<String, Expr>> entrySet, 
      AuthorizationDecision.Decision decision, List<String> unknownPolicyNames, 
      Activation activation) {
    for (Map.Entry<String, Expr> condition : entrySet) {
      try {
        if (matches(condition.getValue(), activation)) {
          return new AuthorizationDecision(decision, 
              new ArrayList<String>(Arrays.asList(new String[] {condition.getKey()})));
        }
      } catch (InterpreterException e) {
        unknownPolicyNames.add(condition.getKey());
      }
    }
    if (unknownPolicyNames.size() > 0) {
      return new AuthorizationDecision(
          AuthorizationDecision.Decision.UNKNOWN, unknownPolicyNames);
    }
    return null;
  }

  /** Evaluate if a condition matches the given Enovy Attributes using CEL library. */
  protected boolean matches(Expr condition, Activation activation) throws InterpreterException {
    // Set up interpretable used in CEL library's eval function.
    List<Descriptor> descriptors = new ArrayList<>();
    RuntimeTypeProvider messageProvider = DescriptorMessageProvider.dynamicMessages(descriptors);
    Dispatcher dispatcher = DefaultDispatcher.create();
    Interpreter interpreter = new DefaultInterpreter(messageProvider, dispatcher);
    Interpretable interpretable = interpreter.createInterpretable(condition);
    // Parse the generated result object to a boolean variable.
    try {
      Object result = interpretable.eval(activation);
      if (result instanceof Boolean) {
        return Boolean.valueOf(result.toString());
      }
      // Throw an InterpreterException if there are missing Envoy Attributes.
      if (result instanceof IncompleteData) {
        throw new InterpreterException.Builder("Incomplete Envoy Attributes to be evaluated.")
            .build(); 
      }
    } catch (InterpreterException e) {
      // If any InterpreterExceptions are catched, throw it and log the error.
      log.log(Level.WARNING, e.toString(), e);
      throw e;
    }
    return false;
  }
}
