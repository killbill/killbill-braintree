/*
 * Copyright 2021 Wovenware, Inc
 *
 * Wovenware licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.plugin.braintree.dao.gen;


import org.killbill.billing.plugin.braintree.dao.gen.tables.BraintreeHppRequests;
import org.killbill.billing.plugin.braintree.dao.gen.tables.BraintreePaymentMethods;
import org.killbill.billing.plugin.braintree.dao.gen.tables.BraintreeResponses;


/**
 * Convenience access to all tables in killbill
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class Tables {

    /**
     * The table <code>killbill.braintree_hpp_requests</code>.
     */
    public static final BraintreeHppRequests BRAINTREE_HPP_REQUESTS = BraintreeHppRequests.BRAINTREE_HPP_REQUESTS;

    /**
     * The table <code>killbill.braintree_payment_methods</code>.
     */
    public static final BraintreePaymentMethods BRAINTREE_PAYMENT_METHODS = BraintreePaymentMethods.BRAINTREE_PAYMENT_METHODS;

    /**
     * The table <code>killbill.braintree_responses</code>.
     */
    public static final BraintreeResponses BRAINTREE_RESPONSES = BraintreeResponses.BRAINTREE_RESPONSES;
}
