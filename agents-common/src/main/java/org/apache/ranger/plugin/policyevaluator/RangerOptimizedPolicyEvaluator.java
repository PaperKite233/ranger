/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.ranger.plugin.policyevaluator;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerServiceDef;
import org.apache.ranger.plugin.policyengine.RangerAccessRequest;
import org.apache.ranger.plugin.policyengine.RangerPolicyEngine;
import org.apache.ranger.plugin.policyengine.RangerPolicyEngineOptions;

import java.util.*;
import java.lang.Math;

public class RangerOptimizedPolicyEvaluator extends RangerDefaultPolicyEvaluator {
    private static final Log LOG = LogFactory.getLog(RangerOptimizedPolicyEvaluator.class);

    private Set<String> groups         = null;
    private Set<String> users          = null;
    private Set<String> accessPerms    = null;
    private boolean     delegateAdmin  = false;
    private boolean     hasAllPerms    = false;
    private boolean     hasPublicGroup = false;


    // For computation of priority
    private static final String RANGER_POLICY_EVAL_MATCH_ANY_PATTERN_STRING   = "*";
    private static final String RANGER_POLICY_EVAL_MATCH_ONE_CHARACTER_STRING = "?";

    private static final int RANGER_POLICY_EVAL_SCORE_DEFAULT                         = 10000;
    private static final int RANGER_POLICY_EVAL_SCORE_DISCOUNT_DENY_POLICY            =  4000;

    private static final int RANGER_POLICY_EVAL_SCORE_MAX_DISCOUNT_RESOURCE          = 100;
    private static final int RANGER_POLICY_EVAL_SCORE_MAX_DISCOUNT_USERSGROUPS       =  25;
    private static final int RANGER_POLICY_EVAL_SCORE_MAX_DISCOUNT_ACCESS_TYPES      =  25;
    private static final int RANGER_POLICY_EVAL_SCORE_MAX_DISCOUNT_CUSTOM_CONDITIONS =  25;

    private static final int RANGER_POLICY_EVAL_SCORE_RESOURCE_DISCOUNT_MATCH_ANY_WILDCARD               = 25;
    private static final int RANGER_POLICY_EVAL_SCORE_RESOURCE_DISCOUNT_HAS_MATCH_ANY_WILDCARD           = 10;
    private static final int RANGER_POLICY_EVAL_SCORE_RESOURCE_DISCOUNT_HAS_MATCH_ONE_CHARACTER_WILDCARD =  5;
    private static final int RANGER_POLICY_EVAL_SCORE_RESOURCE_DISCOUNT_IS_EXCLUDES                      =  5;
    private static final int RANGER_POLICY_EVAL_SCORE_RESORUCE_DISCOUNT_IS_RECURSIVE                     =  5;
    private static final int RANGER_POLICY_EVAL_SCORE_CUSTOM_CONDITION_PENALTY                           =  5;


    @Override
    public void init(RangerPolicy policy, RangerServiceDef serviceDef, RangerPolicyEngineOptions options) {
        if(LOG.isDebugEnabled()) {
            LOG.debug("==> RangerOptimizedPolicyEvaluator.init()");
        }

        super.init(policy, serviceDef, options);

        accessPerms = new HashSet<String>();
        groups = new HashSet<String>();
        users = new HashSet<String>();

        for (RangerPolicy.RangerPolicyItem item : policy.getPolicyItems()) {
            delegateAdmin = delegateAdmin || item.getDelegateAdmin();

            List<RangerPolicy.RangerPolicyItemAccess> policyItemAccesses = item.getAccesses();
            for(RangerPolicy.RangerPolicyItemAccess policyItemAccess : policyItemAccesses) {

                if (policyItemAccess.getIsAllowed()) {
                    String accessType = policyItemAccess.getType();
                    accessPerms.add(accessType);
                }
            }

            groups.addAll(item.getGroups());
            users.addAll(item.getUsers());
        }

        hasAllPerms = checkIfHasAllPerms();

        for (String group : groups) {
            if (group.equalsIgnoreCase(RangerPolicyEngine.GROUP_PUBLIC)) {
                hasPublicGroup = true;
            }
        }

        setEvalOrder(computeEvalOrder());

        if(LOG.isDebugEnabled()) {
            LOG.debug("<== RangerOptimizedPolicyEvaluator.init()");
        }
    }

    class LevelResourceNames implements Comparable<LevelResourceNames> {
        final int level;
        final RangerPolicy.RangerPolicyResource policyResource;

        public LevelResourceNames(int level, RangerPolicy.RangerPolicyResource policyResource) {
            this.level = level;
            this.policyResource = policyResource;
        }

        @Override
        public int compareTo(LevelResourceNames other) {
            // Sort in ascending order of level numbers
            return Integer.compare(this.level, other.level);
        }
    }

    public int computeEvalOrder() {
        if(LOG.isDebugEnabled()) {
            LOG.debug("==> RangerOptimizedPolicyEvaluator.computeEvalOrder()");
        }

        int evalOrder = RANGER_POLICY_EVAL_SCORE_DEFAULT;

        RangerServiceDef                         serviceDef   = getServiceDef();
        List<RangerServiceDef.RangerResourceDef> resourceDefs = serviceDef.getResources();
        RangerPolicy                             policy       = getPolicy();
        List<LevelResourceNames>                 tmpList      = new ArrayList<LevelResourceNames>();

        for (Map.Entry<String, RangerPolicy.RangerPolicyResource> kv : policy.getResources().entrySet()) {
            String                            resourceName   = kv.getKey();
            RangerPolicy.RangerPolicyResource policyResource = kv.getValue();
            List<String>                      resourceValues = policyResource.getValues();

            if(CollectionUtils.isNotEmpty(resourceValues)) {
	            for (RangerServiceDef.RangerResourceDef resourceDef : resourceDefs) {
	                if (resourceName.equals(resourceDef.getName())) {
		                tmpList.add(new LevelResourceNames(resourceDef.getLevel(), policyResource));
	                    break;
	                }
	            }
            }
        }
        Collections.sort(tmpList); // Sort in ascending order of levels

        int resourceDiscount = 0;
        for (LevelResourceNames item : tmpList) {
            // Expect lowest level first
            boolean foundStarWildcard     = false;
            boolean foundQuestionWildcard = false;
            boolean foundMatchAny         = false;

            for (String resourceName : item.policyResource.getValues()) {
                if (resourceName.isEmpty() || resourceName.equals(RANGER_POLICY_EVAL_MATCH_ANY_PATTERN_STRING)) {
                    foundMatchAny = true;
                    break;
                } else if (resourceName.contains(RANGER_POLICY_EVAL_MATCH_ANY_PATTERN_STRING)) {
                    foundStarWildcard = true;
                } else if (resourceName.contains(RANGER_POLICY_EVAL_MATCH_ONE_CHARACTER_STRING)) {
                    foundQuestionWildcard = true;
                }
            }
            if (foundMatchAny) {
                resourceDiscount += RANGER_POLICY_EVAL_SCORE_RESOURCE_DISCOUNT_MATCH_ANY_WILDCARD;
            } else {
                if (foundStarWildcard) {
                    resourceDiscount += RANGER_POLICY_EVAL_SCORE_RESOURCE_DISCOUNT_HAS_MATCH_ANY_WILDCARD;
                } else if (foundQuestionWildcard) {
                    resourceDiscount += RANGER_POLICY_EVAL_SCORE_RESOURCE_DISCOUNT_HAS_MATCH_ONE_CHARACTER_WILDCARD;
                }

                RangerPolicy.RangerPolicyResource resource = item.policyResource;

                if (resource.getIsExcludes()) {
                    resourceDiscount += RANGER_POLICY_EVAL_SCORE_RESOURCE_DISCOUNT_IS_EXCLUDES;
                }

                if (resource.getIsRecursive()) {
                    resourceDiscount += RANGER_POLICY_EVAL_SCORE_RESORUCE_DISCOUNT_IS_RECURSIVE;
                }
            }
        }

        evalOrder -= Math.min(RANGER_POLICY_EVAL_SCORE_MAX_DISCOUNT_RESOURCE, resourceDiscount);

        if (hasPublicGroup) {
            evalOrder -= RANGER_POLICY_EVAL_SCORE_MAX_DISCOUNT_USERSGROUPS;
        } else {
            evalOrder -= Math.min(groups.size() + users.size(), RANGER_POLICY_EVAL_SCORE_MAX_DISCOUNT_USERSGROUPS);
        }

        evalOrder -= Math.round(((float)RANGER_POLICY_EVAL_SCORE_MAX_DISCOUNT_ACCESS_TYPES * accessPerms.size()) / serviceDef.getAccessTypes().size());

        int customConditionsDiscount = RANGER_POLICY_EVAL_SCORE_MAX_DISCOUNT_CUSTOM_CONDITIONS - (RANGER_POLICY_EVAL_SCORE_CUSTOM_CONDITION_PENALTY * this.getCustomConditionsCount());
        if(customConditionsDiscount > 0) {
            evalOrder -= customConditionsDiscount;
        }

        if (policy.isPolicyTypeDeny()) {
            evalOrder -= RANGER_POLICY_EVAL_SCORE_DISCOUNT_DENY_POLICY;
        }

        if(LOG.isDebugEnabled()) {
            LOG.debug("<== RangerOptimizedPolicyEvaluator.computeEvalOrder(), policyName:" + policy.getName() + ", priority:" + evalOrder);
        }

        return evalOrder;
    }

	@Override
	protected boolean isAccessAllowed(String user, Set<String> userGroups, String accessType) {
		if(LOG.isDebugEnabled()) {
			LOG.debug("==> RangerOptimizedPolicyEvaluator.isAccessAllowed(" + user + ", " + userGroups + ", " + accessType + ")");
		}

		boolean ret = false;

		if (hasPublicGroup || users.contains(user) || CollectionUtils.containsAny(groups, userGroups)) {
			if (StringUtils.isEmpty(accessType)) {
				accessType = RangerPolicyEngine.ANY_ACCESS;
			}

			boolean isAnyAccess   = StringUtils.equals(accessType, RangerPolicyEngine.ANY_ACCESS);
			boolean isAdminAccess = StringUtils.equals(accessType, RangerPolicyEngine.ADMIN_ACCESS);

            if (isAnyAccess || (isAdminAccess && delegateAdmin) || hasAllPerms || accessPerms.contains(accessType)) {
                ret = super.isAccessAllowed(user, userGroups, accessType);
            }
        }

        if(LOG.isDebugEnabled()) {
            LOG.debug("<== RangerOptimizedPolicyEvaluator.isAccessAllowed(" + user + ", " + userGroups + ", " + accessType + "): " + ret);
        }

		return ret;
	}

	@Override
    protected boolean isPolicyItemsMatch(RangerAccessRequest request) {
        if(LOG.isDebugEnabled()) {
            LOG.debug("==> RangerOptimizedPolicyEvaluator.isPolicyItemsMatch()");
        }

        boolean ret = false;

        if (hasPublicGroup || users.contains(request.getUser()) || CollectionUtils.containsAny(groups, request.getUserGroups())) {
            // No need to reject based on users and groups

            if (request.isAccessTypeAny() || (request.isAccessTypeDelegatedAdmin() && delegateAdmin) || hasAllPerms || accessPerms.contains(request.getAccessType())) {
                // No need to reject based on aggregated access permissions
                ret = super.isPolicyItemsMatch(request);
            }
        }

        if(LOG.isDebugEnabled()) {
            LOG.debug("<== RangerOptimizedPolicyEvaluator.isPolicyItemsMatch(): " + ret);
        }

        return ret;
    }

	private boolean checkIfHasAllPerms() {
        if(LOG.isDebugEnabled()) {
            LOG.debug("==> RangerOptimizedPolicyEvaluator.checkIfHasAllPerms()");
        }
        boolean result = true;

        List<RangerServiceDef.RangerAccessTypeDef> serviceAccessTypes = getServiceDef().getAccessTypes();
        for (RangerServiceDef.RangerAccessTypeDef serviceAccessType : serviceAccessTypes) {
            if(! accessPerms.contains(serviceAccessType.getName())) {
		result = false;
                break;
            }
        }

        if(LOG.isDebugEnabled()) {
            LOG.debug("==> RangerOptimizedPolicyEvaluator.checkIfHasAllPerms(), " + result);
        }

        return result;
    }

}
