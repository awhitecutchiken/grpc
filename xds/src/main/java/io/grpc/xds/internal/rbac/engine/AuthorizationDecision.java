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

import com.google.common.collect.ImmutableList;
import java.lang.StringBuilder;
import java.util.List;

/** 
 * The AuthorizationDecision class holds authorization decision 
 * returned by CEL Evaluation Engine. 
 */
public class AuthorizationDecision {
  /** The Output enum represents the possible decisions outputted by CEL Evaluation Engine.*/
  public enum Output {
    /** 
     * The Output ALLOW indicates that CEL Evaluate Engine 
     * had authorized the gRPC call and allowed the gRPC call to go through.
     */
    ALLOW,
    /** 
     * The Output DENY indicates that CEL Evaluate Engine 
     * had authorized the gRPC call and denied the gRPC call from going through.
     */
    DENY,
    /** 
     * The Output UNKNOWN indicates that CEL Evaluate Engine 
     * did not have enough information to authorize the gRPC call. 
     * */
    UNKNOWN,
  }

  private final Output decision;
  private final ImmutableList<String> policyNames;

  /**
   * Creates a new authorization decision using the input {@code decision} 
   * for resolving authorization decision
   * and {@code policyNames} for resolving authorization context.
   */
  public AuthorizationDecision(Output decision, List<String> policyNames) {
    this.decision = decision;
    this.policyNames = ImmutableList.copyOf(policyNames);
  }

  /** Returns the authorization decision. */
  public Output getDecision() {
    return this.decision;
  }

  /** Returns the policy list. */
  public ImmutableList<String> getPolicyNames() {
    return this.policyNames;
  }

  @Override
  public String toString() {
    StringBuilder authzStr = new StringBuilder();
    switch (this.decision) {
      case ALLOW: 
        authzStr.append("Authorization Decision: ALLOW. \n");
        break;
      case DENY: 
        authzStr.append("Authorization Decision: DENY. \n");
        break;
      case UNKNOWN: 
        authzStr.append("Authorization Decision: UNKNOWN. \n");
        break;
      default: 
        break;
    }
    for (String policyName : this.policyNames) {
      authzStr.append(policyName + "; \n");
    }
    return authzStr.toString();
  }
}
