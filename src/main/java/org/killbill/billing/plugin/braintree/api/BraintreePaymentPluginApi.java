/*
 * Copyright 2021 Wovenware, Inc
 * Copyright 2020-2021 Equinix, Inc
 * Copyright 2014-2021 The Billing Project, LLC
 *
 * The Billing Project licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.plugin.braintree.api;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.*;

import com.braintreegateway.BraintreeGateway;
import com.braintreegateway.Environment;
import com.braintreegateway.PaymentMethod;
import com.braintreegateway.Result;
import com.braintreegateway.Transaction;
import com.braintreegateway.exceptions.BraintreeException;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.joda.time.DateTime;
import org.killbill.billing.ObjectType;
import org.killbill.billing.account.api.Account;
import org.killbill.billing.catalog.api.Currency;
import org.killbill.billing.osgi.libs.killbill.OSGIConfigPropertiesService;
import org.killbill.billing.osgi.libs.killbill.OSGIKillbillAPI;
import org.killbill.billing.payment.api.PaymentApiException;
import org.killbill.billing.payment.api.PaymentMethodPlugin;
import org.killbill.billing.payment.api.PluginProperty;
import org.killbill.billing.payment.api.TransactionType;
import org.killbill.billing.payment.plugin.api.*;
import org.killbill.billing.plugin.api.PluginProperties;
import org.killbill.billing.plugin.api.core.PluginCustomField;
import org.killbill.billing.plugin.api.payment.PluginPaymentPluginApi;
import org.killbill.billing.plugin.braintree.client.BraintreeClient;
import org.killbill.billing.plugin.braintree.client.BraintreeClientImpl;
import org.killbill.billing.plugin.braintree.core.BraintreeActivator;
import org.killbill.billing.plugin.braintree.core.BraintreeConfigProperties;
import org.killbill.billing.plugin.braintree.core.BraintreeConfigPropertiesConfigurationHandler;
import org.killbill.billing.plugin.braintree.core.BraintreePluginProperties;
import org.killbill.billing.plugin.braintree.core.resources.ExpiredPaymentPolicy;
import org.killbill.billing.plugin.braintree.dao.BraintreeDao;
import org.killbill.billing.plugin.braintree.dao.gen.tables.BraintreePaymentMethods;
import org.killbill.billing.plugin.braintree.dao.gen.tables.BraintreeResponses;
import org.killbill.billing.plugin.braintree.dao.gen.tables.records.BraintreePaymentMethodsRecord;
import org.killbill.billing.plugin.braintree.dao.gen.tables.records.BraintreeResponsesRecord;
import org.killbill.billing.util.api.CustomFieldApiException;
import org.killbill.billing.util.callcontext.CallContext;
import org.killbill.billing.util.callcontext.TenantContext;
import org.killbill.billing.util.customfield.CustomField;
import org.killbill.clock.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

public class BraintreePaymentPluginApi extends PluginPaymentPluginApi<BraintreeResponsesRecord, BraintreeResponses, BraintreePaymentMethodsRecord, BraintreePaymentMethods> {

    private static final Logger logger = LoggerFactory.getLogger(BraintreePaymentPluginApi.class);
    private final BraintreeDao dao;
    private final BraintreeConfigPropertiesConfigurationHandler braintreeConfigPropertiesConfigurationHandler;

    public BraintreePaymentPluginApi(final BraintreeConfigPropertiesConfigurationHandler braintreeConfigPropertiesConfigurationHandler,
                                     final OSGIKillbillAPI killbillAPI,
                                     final OSGIConfigPropertiesService configProperties,
                                     final Clock clock,
                                     final BraintreeDao dao) {
        super(killbillAPI, configProperties, clock, dao);
        this.braintreeConfigPropertiesConfigurationHandler = braintreeConfigPropertiesConfigurationHandler;
        this.dao = dao;
    }

    @Override
    public PaymentTransactionInfoPlugin authorizePayment(UUID kbAccountId, UUID kbPaymentId, UUID kbTransactionId,
                                                         UUID kbPaymentMethodId, BigDecimal amount, Currency currency, Iterable<PluginProperty> properties,
                                                         CallContext context) throws PaymentPluginApiException {
        return executeInitialTransaction(TransactionType.AUTHORIZE, kbAccountId, kbPaymentId, kbTransactionId, kbPaymentMethodId, amount, currency, properties, context);
    }

    @Override
    public PaymentTransactionInfoPlugin capturePayment(UUID kbAccountId, UUID kbPaymentId, UUID kbTransactionId,
                                                       UUID kbPaymentMethodId, BigDecimal amount, Currency currency, Iterable<PluginProperty> properties,
                                                       CallContext context) throws PaymentPluginApiException {
        return executeFollowUpTransaction(TransactionType.CAPTURE,
                                          new TransactionExecutor<Result<Transaction>>() {
                                              @Override
                                              public Result<Transaction> execute(final Account account, final BraintreePaymentMethodsRecord paymentMethodsRecord, final BraintreeResponsesRecord previousResponse) throws BraintreeException {
                                                  return buildBraintreeClient(context).submitTransactionForSettlement(previousResponse.getBraintreeId(), amount);
                                              }
                                          },
                                          kbAccountId,
                                          kbPaymentId,
                                          kbTransactionId,
                                          kbPaymentMethodId,
                                          amount,
                                          currency,
                                          properties,
                                          context);
    }

    @Override
    public PaymentTransactionInfoPlugin purchasePayment(UUID kbAccountId, UUID kbPaymentId, UUID kbTransactionId,
                                                        UUID kbPaymentMethodId, BigDecimal amount, Currency currency, Iterable<PluginProperty> properties,
                                                        CallContext context) throws PaymentPluginApiException {
        final BraintreeResponsesRecord braintreeResponsesRecord;
        try {
            braintreeResponsesRecord = dao.updateResponse(kbTransactionId, properties, context.getTenantId());
        } catch (final SQLException e) {
            throw new PaymentPluginApiException("Encountered a database error while attempting to complete purchase.", e);
        }

        if (braintreeResponsesRecord == null) {
            // We don't have any record for that payment: we want to trigger an actual purchase (auto-capture) call
            return executeInitialTransaction(TransactionType.PURCHASE, kbAccountId, kbPaymentId, kbTransactionId, kbPaymentMethodId, amount, currency, properties, context);
        }
        else {
            // We already have a record for that payment transaction and we just updated the response row with additional properties
            return buildPaymentTransactionInfoPlugin(braintreeResponsesRecord);
        }
    }

    @Override
    public PaymentTransactionInfoPlugin voidPayment(UUID kbAccountId, UUID kbPaymentId, UUID kbTransactionId,
                                                    UUID kbPaymentMethodId, Iterable<PluginProperty> properties, CallContext context)
            throws PaymentPluginApiException {
        return executeFollowUpTransaction(TransactionType.VOID,
                                          new TransactionExecutor<Result<Transaction>>() {
                                              @Override
                                              public Result<Transaction> execute(final Account account, final BraintreePaymentMethodsRecord paymentMethodsRecord, final BraintreeResponsesRecord previousResponse) throws BraintreeException {
                                                  return buildBraintreeClient(context).voidTransaction(previousResponse.getBraintreeId());
                                              }
                                          },
                                          kbAccountId,
                                          kbPaymentId,
                                          kbTransactionId,
                                          kbPaymentMethodId,
                                          null,
                                          null,
                                          properties,
                                          context);
    }

    @Override
    public PaymentTransactionInfoPlugin creditPayment(UUID kbAccountId, UUID kbPaymentId, UUID kbTransactionId,
                                                      UUID kbPaymentMethodId, BigDecimal amount, Currency currency, Iterable<PluginProperty> properties,
                                                      CallContext context) throws PaymentPluginApiException {
        //NOTE: Credit transactions are disabled by default in Braintree and require special authorization. Use of refunds is encouraged whenever possible
        final BraintreeResponsesRecord braintreeResponsesRecord;
        try {
            braintreeResponsesRecord = dao.updateResponse(kbTransactionId, properties, context.getTenantId());
        } catch (final SQLException e) {
            throw new PaymentPluginApiException("Encountered a database error while attempting to credit payment.", e);
        }

        if (braintreeResponsesRecord == null) {
            return executeInitialTransaction(TransactionType.CREDIT, kbAccountId, kbPaymentId, kbTransactionId, kbPaymentMethodId, amount, currency, properties, context);
        }
        else {
            return buildPaymentTransactionInfoPlugin(braintreeResponsesRecord);
        }
    }

    @Override
    public PaymentTransactionInfoPlugin refundPayment(UUID kbAccountId, UUID kbPaymentId, UUID kbTransactionId,
                                                      UUID kbPaymentMethodId, BigDecimal amount, Currency currency, Iterable<PluginProperty> properties,
                                                      CallContext context) throws PaymentPluginApiException {
        return executeFollowUpTransaction(TransactionType.REFUND,
                                          new TransactionExecutor<Result<Transaction>>() {
                                              @Override
                                              public Result<Transaction> execute(final Account account, final BraintreePaymentMethodsRecord paymentMethodsRecord, final BraintreeResponsesRecord previousResponse) throws BraintreeException {
                                                  return buildBraintreeClient(context).refundTransaction(previousResponse.getBraintreeId(), amount);
                                              }
                                          },
                                          kbAccountId,
                                          kbPaymentId,
                                          kbTransactionId,
                                          kbPaymentMethodId,
                                          amount,
                                          currency,
                                          properties,
                                          context);
    }

    @Override
    public List<PaymentTransactionInfoPlugin> getPaymentInfo(UUID kbAccountId, UUID kbPaymentId,
                                                             Iterable<PluginProperty> properties, TenantContext context) throws PaymentPluginApiException {
        final List<PaymentTransactionInfoPlugin> transactions = super.getPaymentInfo(kbAccountId, kbPaymentId, properties, context);
        if (transactions.isEmpty()) {
            // We don't know about this payment (maybe it was aborted in a control plugin)
            return transactions;
        }

        // Check if a payment needs to be canceled
        final ExpiredPaymentPolicy expiredPaymentPolicy = new ExpiredPaymentPolicy(clock, braintreeConfigPropertiesConfigurationHandler.getConfigurable(context.getTenantId()));
        final BraintreePaymentTransactionInfoPlugin transactionToExpire = expiredPaymentPolicy.isExpired(transactions);
        if (transactionToExpire != null) {
            logger.info("Canceling expired Braintree transaction {} (created {})", transactionToExpire.getBraintreeResponsesRecord().getBraintreeId(), transactionToExpire.getBraintreeResponsesRecord().getCreatedDate());
            final Map additionalMetadata = ImmutableMap.builder()
                                                       .put(BraintreePluginProperties.PROPERTY_OVERRIDDEN_TRANSACTION_STATUS,
                                                            PaymentPluginStatus.CANCELED.toString())
                                                       .put("message",
                                                            "Payment Expired - Cancelled by Janitor")
                                                       .build();
            try {
                dao.updateResponse(transactionToExpire.getBraintreeResponsesRecord(), additionalMetadata);
            } catch (final SQLException e) {
                throw new PaymentPluginApiException("Unable to update expired payment", e);
            }

            // Reload payment
            return super.getPaymentInfo(kbAccountId, kbPaymentId, properties, context);
        }

        final BraintreeClient braintreeClient = buildBraintreeClient(context);

        // Refresh, if needed
        boolean wasRefreshed = false;
        for (final PaymentTransactionInfoPlugin transaction : transactions) {
            String braintreeStatus = PluginProperties.getValue(BraintreePluginProperties.PROPERTY_BT_TRANSACTION_STATUS,
                                                               BraintreePluginProperties.PROPERTY_FALLBACK_VALUE,
                                                               transaction.getProperties());

            if (transaction.getStatus() == PaymentPluginStatus.PENDING || transaction.getStatus() == PaymentPluginStatus.UNDEFINED
                || (transaction.getStatus() == PaymentPluginStatus.PROCESSED && !BraintreePaymentTransactionInfoPlugin.isDoneProcessingInBraintree(braintreeStatus))) {
                String braintreeTransactionId = ((BraintreePaymentTransactionInfoPlugin) transaction).getBraintreeResponsesRecord().getBraintreeId();
                logger.info("Refreshing kbTransaction: {}, btTransaction {}", transaction.getKbPaymentId(), braintreeTransactionId);
                try{
                    String updatedStatus = braintreeClient.getTransactionStatus(braintreeTransactionId).toString();
                    Iterable<PluginProperty> updatedProperties = ImmutableList.of(
                            new PluginProperty(BraintreePluginProperties.PROPERTY_BT_TRANSACTION_STATUS, updatedStatus, true)
                                                                                 );
                    dao.updateResponse(transaction.getKbTransactionPaymentId(), updatedProperties, context.getTenantId());
                    wasRefreshed = true;
                } catch (BraintreeException e) {
                    throw new PaymentPluginApiException("Error connecting to Braintree", e);
                } catch (SQLException e) {
                    throw new PaymentPluginApiException("Could not load payment information from database", e);
                }
            }
        }

        return wasRefreshed ? super.getPaymentInfo(kbAccountId, kbPaymentId, properties, context) : transactions;
    }

    @Override
    public void addPaymentMethod(final UUID kbAccountId,
                                 final UUID kbPaymentMethodId,
                                 final PaymentMethodPlugin paymentMethodProps,
                                 final boolean setDefault,
                                 final Iterable<PluginProperty> properties,
                                 final CallContext context) throws PaymentPluginApiException {
        // Support both body and query parameters based plugin properties
        final Iterable<PluginProperty> allProperties = PluginProperties.merge(paymentMethodProps.getProperties(), properties);

        final BraintreeClient braintreeClient = buildBraintreeClient(context);
        final PaymentMethod braintreePaymentMethod;

        final String braintreeNonce = PluginProperties.findPluginPropertyValue(BraintreePluginProperties.PROPERTY_BT_NONCE, allProperties);
        if (braintreeNonce != null) {
            String braintreeCustomerId = PluginProperties.findPluginPropertyValue(BraintreePluginProperties.PROPERTY_BT_CUSTOMER_ID, allProperties);
            if (braintreeCustomerId == null) {
                braintreeCustomerId = getCustomerIdCustomField(kbAccountId, context);
                if (braintreeCustomerId == null) {
                    throw new PaymentPluginApiException("Could not create payment method in Braintree: missing {} plugin property", BraintreePluginProperties.PROPERTY_BT_CUSTOMER_ID);
                }
            } else {
                // Automatically create the custom field, if needed
                setCustomerIdCustomField(braintreeCustomerId, kbAccountId, context);
            }

            // New payment method for KillBill and Braintree
            final String braintreePaymentMethodToken = kbPaymentMethodId.toString();
            final String braintreePaymentMethodType = PluginProperties.getValue(BraintreePluginProperties.PROPERTY_PAYMENT_METHOD_TYPE,
                                                                                BraintreePluginProperties.PaymentMethodType.CARD.toString(),
                                                                                allProperties);
            logger.info("Creating payment method with nonce={}, paymentMethodId={}, paymentMethodType={}", braintreeNonce, braintreePaymentMethodToken, braintreePaymentMethodType);
            try {
                final Result<? extends PaymentMethod> result = braintreeClient.createPaymentMethod(braintreeCustomerId,
                                                                                                   braintreePaymentMethodToken,
                                                                                                   braintreeNonce,
                                                                                                   BraintreePluginProperties.PaymentMethodType.valueOf(braintreePaymentMethodType.toUpperCase()));
                if (!result.isSuccess() || !result.getTarget().getToken().equals(braintreePaymentMethodToken)) {
                    throw new BraintreeException(result.getMessage());
                }

                braintreePaymentMethod = result.getTarget();
            } catch (final BraintreeException e) {
                throw new PaymentPluginApiException("Could not create payment method in Braintree", e);
            }
        } else {
            // Otherwise, payment method was created in Braintree (sync use-case), in which case the external payment method id is the token
            braintreePaymentMethod = braintreeClient.getPaymentMethod(paymentMethodProps.getExternalPaymentMethodId());
        }

        final Map<String, Object> additionalDataMap = BraintreePluginProperties.toAdditionalDataMap(braintreePaymentMethod);

        final DateTime utcNow = clock.getUTCNow();
        try {
            dao.addPaymentMethod(kbAccountId, kbPaymentMethodId, setDefault, additionalDataMap, braintreePaymentMethod.getToken(), utcNow, context.getTenantId());
        } catch (final SQLException e) {
            throw new PaymentPluginApiException("Unable to add payment method", e);
        }
    }

    @Override
    public void deletePaymentMethod(UUID kbAccountId, UUID kbPaymentMethodId, Iterable<PluginProperty> properties,
                                    CallContext context) throws PaymentPluginApiException {
        // Retrieve our currently known payment method
        final BraintreePaymentMethodsRecord braintreePaymentMethodsRecord;
        try {
            braintreePaymentMethodsRecord = dao.getPaymentMethod(kbPaymentMethodId, context.getTenantId());
        } catch (final SQLException e) {
            throw new PaymentPluginApiException("Unable to retrieve payment method", e);
        }

        // Delete in Braintree
        String braintreePaymentMethodToken = braintreePaymentMethodsRecord.getBraintreeId();
        try {
            Result<? extends PaymentMethod> result = buildBraintreeClient(context).deletePaymentMethod(braintreePaymentMethodToken);
            if(!result.isSuccess()) throw new BraintreeException(result.getMessage());
        } catch (final BraintreeException e) {
            throw new PaymentPluginApiException("Could not delete payment method in Braintree", e);
        }

        super.deletePaymentMethod(kbAccountId, kbPaymentMethodId, properties, context);
    }

    @Override
    public PaymentMethodPlugin getPaymentMethodDetail(UUID kbAccountId, UUID kbPaymentMethodId,
                                                      Iterable<PluginProperty> properties, TenantContext context) throws PaymentPluginApiException {
        final BraintreePaymentMethodsRecord record;
        try {
            record = dao.getPaymentMethod(kbPaymentMethodId, context.getTenantId());
        } catch (final SQLException e) {
            throw new PaymentPluginApiException("Unable to retrieve payment method for kbPaymentMethodId " + kbPaymentMethodId, e);
        }

        if (record == null) {
            // Known in KB but deleted in Braintree?
            return new BraintreePaymentMethodPlugin(kbPaymentMethodId,
                                                    null,
                                                    false,
                                                    ImmutableList.<PluginProperty>of());
        } else {
            return buildPaymentMethodPlugin(record);
        }
    }

    @Override
    public List<PaymentMethodInfoPlugin> getPaymentMethods(final UUID kbAccountId,
                                                           final boolean refreshFromGateway,
                                                           final Iterable<PluginProperty> properties,
                                                           final CallContext context) throws PaymentPluginApiException {
        // To retrieve all payment methods in Braintree, retrieve the Braintree customer id (custom field on the account)
        final String braintreeCustomerId = getCustomerIdCustomField(kbAccountId, context);
        // If refreshFromGateway isn't set or there is no customer id yet, simply read our tables
        if (!refreshFromGateway || braintreeCustomerId == null) {
            return super.getPaymentMethods(kbAccountId, refreshFromGateway, properties, context);
        }

        // Retrieve our currently known payment methods
        final Map<String, BraintreePaymentMethodsRecord> existingPaymentMethodByToken = new HashMap<>();
        try {
            final List<BraintreePaymentMethodsRecord> existingBraintreePaymentMethodRecords = dao.getPaymentMethods(kbAccountId, context.getTenantId());
            for (final BraintreePaymentMethodsRecord existingBraintreePaymentMethodRecord : existingBraintreePaymentMethodRecords) {
                // Braintree id is the payment method token
                existingPaymentMethodByToken.put(existingBraintreePaymentMethodRecord.getBraintreeId(), existingBraintreePaymentMethodRecord);
            }
        } catch (final SQLException e) {
            throw new PaymentPluginApiException("Unable to retrieve existing payment methods", e);
        }

        // Sync Braintree payment methods (source of truth)
        try {
            final List<? extends PaymentMethod> braintreePaymentMethods = buildBraintreeClient(context).getPaymentMethods(braintreeCustomerId);
            syncPaymentMethods(kbAccountId, braintreePaymentMethods, existingPaymentMethodByToken, context);
        } catch (final BraintreeException e) {
            throw new PaymentPluginApiException("Error connecting to Braintree", e);
        } catch (final PaymentApiException e) {
            throw new PaymentPluginApiException("Error creating payment method", e);
        } catch (final SQLException e) {
            throw new PaymentPluginApiException("Error creating payment method", e);
        }

        for (final BraintreePaymentMethodsRecord braintreePaymentMethodsRecord : existingPaymentMethodByToken.values()) {
            logger.info("Deactivating local Braintree payment method {} - not found in Braintree", braintreePaymentMethodsRecord.getBraintreeId());
            super.deletePaymentMethod(kbAccountId, UUID.fromString(braintreePaymentMethodsRecord.getKbPaymentMethodId()), properties, context);
        }

        // Refresh the state
        return super.getPaymentMethods(kbAccountId, false, properties, context);
    }

    @Override
    public HostedPaymentPageFormDescriptor buildFormDescriptor(UUID kbAccountId, Iterable<PluginProperty> customFields,
                                                               Iterable<PluginProperty> properties, CallContext context) throws PaymentPluginApiException {
        throw new PaymentPluginApiException("INTERNAL", "#buildFormDescriptor is not implemented nor applicable to Braintree plugin.");
    }

    @Override
    public GatewayNotification processNotification(String notification, Iterable<PluginProperty> properties,
                                                   CallContext context) throws PaymentPluginApiException {
        throw new PaymentPluginApiException("INTERNAL", "#processNotification not yet implemented, please contact support@killbill.io");
    }

    //Superclass abstract methods

    @Override
    protected PaymentTransactionInfoPlugin buildPaymentTransactionInfoPlugin(BraintreeResponsesRecord record) {
        return BraintreePaymentTransactionInfoPlugin.build(record);
    }

    @Override
    protected PaymentMethodPlugin buildPaymentMethodPlugin(BraintreePaymentMethodsRecord record) {
        return BraintreePaymentMethodPlugin.build(record);
    }

    @Override
    protected PaymentMethodInfoPlugin buildPaymentMethodInfoPlugin(BraintreePaymentMethodsRecord record) {
        return BraintreePaymentMethodInfoPlugin.build(record);
    }

    @Override
    protected String getPaymentMethodId(BraintreePaymentMethodsRecord record){
        return record.getKbPaymentMethodId();
    }

    //Private

    private abstract static class TransactionExecutor<T> {

        public T execute(final Account account, final BraintreePaymentMethodsRecord paymentMethodsRecord) throws BraintreeException {
            throw new UnsupportedOperationException();

        }

        public T execute(final Account account, final BraintreePaymentMethodsRecord paymentMethodsRecord, final BraintreeResponsesRecord previousResponse) throws BraintreeException {
            throw new UnsupportedOperationException();
        }
    }

    private void updateResponseWithAdditionalProperties(final UUID kbTransactionId, final Iterable<PluginProperty> properties, final UUID tenantId) throws PaymentPluginApiException {
        try {
            dao.updateResponse(kbTransactionId, properties, tenantId);
        } catch (final SQLException e) {
            throw new PaymentPluginApiException("SQL exception when updating response", e);
        }
    }

    private PaymentTransactionInfoPlugin executeInitialTransaction(final TransactionType transactionType,
                                                                   final UUID kbAccountId,
                                                                   final UUID kbPaymentId,
                                                                   final UUID kbTransactionId,
                                                                   final UUID kbPaymentMethodId,
                                                                   final BigDecimal amount,
                                                                   final Currency currency,
                                                                   final Iterable<PluginProperty> properties,
                                                                   final CallContext context) throws PaymentPluginApiException {
        final String braintreeCustomerId = PluginProperties.getValue(BraintreePluginProperties.PROPERTY_BT_CUSTOMER_ID,
                                                                     BraintreePluginProperties.PROPERTY_FALLBACK_VALUE, properties);
        if(!braintreeCustomerId.equals(BraintreePluginProperties.PROPERTY_FALLBACK_VALUE)){
            setCustomerIdCustomField(braintreeCustomerId, kbAccountId, context);
        }
        return executeInitialTransaction(transactionType,
                                         new TransactionExecutor<Result<Transaction>>() {
                                             @Override
                                             public Result<Transaction> execute(final Account account, final BraintreePaymentMethodsRecord paymentMethodsRecord) {
                                                 final BraintreeClient braintreeClient = buildBraintreeClient(context);
                                                 String braintreePaymentMethodNonce = braintreeClient.createNonceFromPaymentMethodToken(paymentMethodsRecord.getBraintreeId());
                                                 if(transactionType == TransactionType.CREDIT){
                                                     return braintreeClient.creditTransaction(amount,
                                                                                              getCustomerIdCustomField(kbAccountId, context),
                                                                                              braintreePaymentMethodNonce);
                                                 }
                                                 else{
                                                     boolean submitForSettlement = transactionType != TransactionType.AUTHORIZE;
                                                     // Make sure to pass the order id to avoid confusing the Braintree duplicate checker
                                                     return braintreeClient.saleTransaction(
                                                             kbTransactionId.toString(),
                                                             amount,
                                                             getCustomerIdCustomField(kbAccountId, context),
                                                             braintreePaymentMethodNonce,
                                                             submitForSettlement);
                                                 }
                                             }
                                         },
                                         kbAccountId,
                                         kbPaymentId,
                                         kbTransactionId,
                                         kbPaymentMethodId,
                                         amount,
                                         currency,
                                         properties,
                                         context);
    }

    private PaymentTransactionInfoPlugin executeInitialTransaction(final TransactionType transactionType,
                                                                   final TransactionExecutor<Result<Transaction>> transactionExecutor,
                                                                   final UUID kbAccountId,
                                                                   final UUID kbPaymentId,
                                                                   final UUID kbTransactionId,
                                                                   final UUID kbPaymentMethodId,
                                                                   final BigDecimal amount,
                                                                   final Currency currency,
                                                                   final Iterable<PluginProperty> properties,
                                                                   final TenantContext context) throws PaymentPluginApiException {
        final Account account = getAccount(kbAccountId, context);
        final BraintreePaymentMethodsRecord nonNullPaymentMethodsRecord = getBraintreePaymentMethodsRecord(kbPaymentMethodId, context);
        final DateTime utcNow = clock.getUTCNow();

        Result<Transaction> response;
        try {
            response = transactionExecutor.execute(account, nonNullPaymentMethodsRecord);
        } catch (final BraintreeException e) {
            throw new PaymentPluginApiException("Error connecting to Braintree", e);
        }

        try {
            final BraintreeResponsesRecord responsesRecord = dao.addResponse(kbAccountId, kbPaymentId, kbTransactionId, transactionType, amount, currency, response, utcNow, context.getTenantId());
            return BraintreePaymentTransactionInfoPlugin.build(responsesRecord);
        } catch (final SQLException e) {
            throw new PaymentPluginApiException("Payment went through, but we encountered a database error. Payment details: " + response.toString(), e);
        }
    }

    private PaymentTransactionInfoPlugin executeFollowUpTransaction(final TransactionType transactionType,
                                                                    final TransactionExecutor<Result<Transaction>> transactionExecutor,
                                                                    final UUID kbAccountId,
                                                                    final UUID kbPaymentId,
                                                                    final UUID kbTransactionId,
                                                                    final UUID kbPaymentMethodId,
                                                                    @Nullable final BigDecimal amount,
                                                                    @Nullable final Currency currency,
                                                                    final Iterable<PluginProperty> properties,
                                                                    final TenantContext context) throws PaymentPluginApiException {
        final Account account = getAccount(kbAccountId, context);
        final BraintreePaymentMethodsRecord nonNullPaymentMethodsRecord = getBraintreePaymentMethodsRecord(kbPaymentMethodId, context);

        final BraintreeResponsesRecord previousResponse;
        try {
            previousResponse = dao.getSuccessfulAuthorizationResponse(kbPaymentId, context.getTenantId());
            if (previousResponse == null) {
                throw new PaymentPluginApiException(null, "Unable to retrieve previous payment response for kbTransactionId " + kbTransactionId);
            }
        } catch (final SQLException e) {
            throw new PaymentPluginApiException("Unable to retrieve previous payment response for kbTransactionId " + kbTransactionId, e);
        }

        final DateTime utcNow = clock.getUTCNow();

        final Result<Transaction> response;
        try {
            response = transactionExecutor.execute(account, nonNullPaymentMethodsRecord, previousResponse);
        } catch (final BraintreeException e) {
            throw new PaymentPluginApiException("Error connecting to Braintree", e);
        }

        try {
            final BraintreeResponsesRecord responsesRecord = dao.addResponse(kbAccountId, kbPaymentId, kbTransactionId, transactionType, amount, currency, response, utcNow, context.getTenantId());
            return BraintreePaymentTransactionInfoPlugin.build(responsesRecord);
        } catch (final SQLException e) {
            throw new PaymentPluginApiException("Payment went through, but we encountered a database error. Payment details: " + (response.toString()), e);
        }
    }

    private void syncPaymentMethods(final UUID kbAccountId,
                                    final Iterable<? extends PaymentMethod> braintreePaymentMethods,
                                    final Map<String, BraintreePaymentMethodsRecord> existingPaymentMethodByToken,
                                    final CallContext context) throws PaymentApiException, SQLException {
        for (final PaymentMethod paymentMethod : braintreePaymentMethods) {
            final Map<String, Object> additionalDataMap = BraintreePluginProperties.toAdditionalDataMap(paymentMethod);

            // We remove it here to build the list of local payment methods to delete
            final BraintreePaymentMethodsRecord existingPaymentMethodRecord = existingPaymentMethodByToken.remove(paymentMethod.getToken());
            if (existingPaymentMethodRecord == null) {
                // We don't know about it yet, create it
                logger.info("Creating new local Braintree payment method {}", paymentMethod.getToken());
                final List<PluginProperty> properties = PluginProperties.buildPluginProperties(additionalDataMap);
                final PaymentMethodPlugin paymentMethodInfo = new BraintreePaymentMethodPlugin(null,
                                                                                               paymentMethod.getToken(),
                                                                                               paymentMethod.isDefault(),
                                                                                               properties);
                try {
                    killbillAPI.getPaymentApi().addPaymentMethod(getAccount(kbAccountId, context),
                                                                 paymentMethod.getToken(),
                                                                 BraintreeActivator.PLUGIN_NAME,
                                                                 paymentMethod.isDefault(),
                                                                 paymentMethodInfo,
                                                                 properties,
                                                                 context);
                } catch (final PaymentApiException e) {
                    // In case of errors, opportunistically continue
                    logger.warn("Unable to create new local Braintree payment method {}", paymentMethod.getToken(), e.getCause());
                }
            } else {
                logger.info("Updating existing local Braintree payment method {}", existingPaymentMethodRecord.getKbPaymentMethodId());
                dao.updatePaymentMethod(UUID.fromString(existingPaymentMethodRecord.getKbPaymentMethodId()),
                                        additionalDataMap,
                                        paymentMethod.getToken(),
                                        clock.getUTCNow(),
                                        context.getTenantId());
            }
        }
    }

    private void setCustomerIdCustomField(String braintreeCustomerId, UUID kbAccountId, CallContext context) throws PaymentPluginApiException{
        final String existingCustomerId = getCustomerIdCustomField(kbAccountId, context);
        if (existingCustomerId == null) {
            // Add magic custom field
            logger.info("Mapping kbAccountId {} to Braintree customer {}", kbAccountId, braintreeCustomerId);
            try {
                killbillAPI.getCustomFieldUserApi().addCustomFields(ImmutableList.of(new PluginCustomField(kbAccountId,
                                                                                                           ObjectType.ACCOUNT,
                                                                                                           BraintreePluginProperties.MAGIC_FIELD_BT_CUSTOMER_ID,
                                                                                                           braintreeCustomerId,
                                                                                                           clock.getUTCNow())), context);
            } catch (CustomFieldApiException e) {
                throw new PaymentPluginApiException("Unable to add custom field", e);
            }
        } else if (!braintreeCustomerId.equals(BraintreePluginProperties.PROPERTY_FALLBACK_VALUE) && !braintreeCustomerId.equals(existingCustomerId)) {
            throw new PaymentPluginApiException("USER", "Unable to add custom field : customerId is " + braintreeCustomerId + " but account already mapped to " + existingCustomerId);
        }
    }

    private String getCustomerIdCustomField(final UUID kbAccountId, final CallContext context) {
        final List<CustomField> customFields = killbillAPI.getCustomFieldUserApi().getCustomFieldsForAccountType(kbAccountId, ObjectType.ACCOUNT, context);
        String braintreeCustomerId = null;
        for (final CustomField customField : customFields) {
            if (customField.getFieldName().equals(BraintreePluginProperties.MAGIC_FIELD_BT_CUSTOMER_ID)) {
                braintreeCustomerId = customField.getFieldValue();
                break;
            }
        }
        return braintreeCustomerId;
    }

    private BraintreePaymentMethodsRecord getBraintreePaymentMethodsRecord(@Nullable final UUID kbPaymentMethodId, final TenantContext context) throws PaymentPluginApiException {
        BraintreePaymentMethodsRecord paymentMethodsRecord = null;

        if (kbPaymentMethodId != null) {
            try {
                paymentMethodsRecord = dao.getPaymentMethod(kbPaymentMethodId, context.getTenantId());
            } catch (final SQLException e) {
                throw new PaymentPluginApiException("Failed to retrieve payment method", e);
            }
        }

        return MoreObjects.firstNonNull(paymentMethodsRecord, emptyRecord(kbPaymentMethodId));
    }

    private BraintreePaymentMethodsRecord emptyRecord(@Nullable final UUID kbPaymentMethodId) {
        final BraintreePaymentMethodsRecord record = new BraintreePaymentMethodsRecord();
        if (kbPaymentMethodId != null) {
            record.setKbPaymentMethodId(kbPaymentMethodId.toString());
        }
        return record;
    }

    private BraintreeClient buildBraintreeClient(final TenantContext tenantContext) {
        final BraintreeConfigProperties config = braintreeConfigPropertiesConfigurationHandler.getConfigurable(tenantContext.getTenantId());
        final BraintreeGateway braintreeGateway = new BraintreeGateway(
                Environment.parseEnvironment(config.getBtEnvironment()),
                config.getBtMerchantId(),
                config.getBtPublicKey(),
                config.getBtPrivateKey()
        );
        return new BraintreeClientImpl(braintreeGateway);
    }
}
