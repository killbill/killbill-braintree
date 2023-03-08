# killbill-braintree-plugin
![Maven Central](https://img.shields.io/maven-central/v/org.kill-bill.billing.plugin.java/braintree-plugin?color=blue&label=Maven%20Central)

Plugin to use [Braintree](https://www.braintreepayments.com/) as a gateway.

A full end-to-end integration demo is available [here](https://github.com/killbill/killbill-braintree-demo).

## Kill Bill compatibility
-----------------------

| Plugin version | Kill Bill version |
| -------------: | ----------------: |
| 0.0.y          | 0.22.z            |
| 1.0.y          | 0.24.z            |

## Requirements

* An active Braintree account is required for using the plugin. A Braintree sandbox account may be used for testing purposes.
* The plugin needs a database. The latest version of the schema can be found [here](https://github.com/killbill/killbill-braintree/tree/master/src/main/resources).

## Build

```
mvn clean install -DskipTests
```

## Installation

Locally:

```
kpm install_java_plugin braintree-plugin --from-source-file target/braintree-plugin-*-SNAPSHOT.jar --destination /var/tmp/bundles
```

## Setup

In order to use the plugin, Braintree credentials are required. These can be obtained as explained below:

1. Create a new Braintree sandbox account by signing up [here](https://www.braintreepayments.com/sandbox). 
2. Login to your account and obtain **Merchant Id**, **Public key**, and **Private key**. See [Braintree - Gateway Credentials](https://articles.braintreepayments.com/control-panel/important-gateway-credentials) for more information about retrieving these values from your account.

## Configuration

Configure the plugin with the Braintree credentials obtained above as follows:

```
curl -v \
 -X POST \
 -u admin:password \
 -H 'X-Killbill-ApiKey: bob' \
 -H 'X-Killbill-ApiSecret: lazar' \
 -H 'X-Killbill-CreatedBy: admin' \
 -H 'Content-Type: text/plain' \
 -d 'org.killbill.billing.plugin.braintree.btEnvironment=sandbox
org.killbill.billing.plugin.braintree.btMerchantId=xxx
org.killbill.billing.plugin.braintree.btPublicKey=xxx
org.killbill.billing.plugin.braintree.btPrivateKey=xxx' \
 http://127.0.0.1:8080/1.0/kb/tenants/uploadPluginConfig/killbill-braintree
```

Alternatively, you can add the above properties to the `killbill.properties` as explained in the [Kill Bill configuration guide](https://docs.killbill.io/latest/userguide_configuration.html) or set the following environment variables:

```bash
BRAINTREE_ENVIRONMENT
BRAINTREE_MERCHANT_ID
BRAINTREE_PUBLIC_KEY
BRAINTREE_PRIVATE_KEY
```

Some important notes:

* Use `btEnvironment=sandbox` only for when a sandbox account is used. Other possible values include **development**, **qa**, and **production**. See Braintree documentation for details.
* The plugin attempts to load the credentials either from the per-tenant configuration or the Kill Bill properties file  while the unit tests require the properties to be set as environment variables.
* In order to facilitate automated testing, you should disable all fraud detection within your sandbox account. These can generate gateway rejection errors when processing multiple test transactions. In particular make sure to disable [Duplicate Transaction Checking](https://articles.braintreepayments.com/control-panel/transactions/duplicate-checking#configuring-duplicate-transaction-checking).

## Testing

1. Ensure that the plugin is installed and configured as explained above.

2. [Create a customer](https://developer.paypal.com/braintree/articles/control-panel/vault/create) in Braintree with [test card details](https://developer.paypal.com/braintree/docs/reference/general/testing#valid-card-numbers). Save the **Customer ID** generated for future reference (it should be something like **620594365**).

3. [Create](https://killbill.github.io/slate/?shell#account-create-an-account) a Kill Bill account. Save the **accountId** for further use.

4. Create a payment method in Kill Bill using a [fake valid nonce](https://developer.paypal.com/braintree/docs/reference/general/testing#payment-method-nonces) and the braintree customer id as follows:

```
curl -v \
     -u admin:password \
     -H "X-Killbill-ApiKey: bob" \
     -H "X-Killbill-ApiSecret: lazar" \
     -H "Content-Type: application/json" \
     -H "X-Killbill-CreatedBy: demo" \
     -X POST \
     --data-binary '{
       "pluginName": "killbill-braintree",
       "pluginInfo": {
         "properties": [
           {
             "key": "bt_nonce",
             "value": "fake-valid-nonce"
           },
           {
             "key": "bt_customer_id",
             "value": "xxx"
           }
         ]
       }
     }' \
     "http://127.0.0.1:8080/1.0/kb/accounts/<ACCOUNT_ID>/paymentMethods?isDefault=true"
```

5. Use the `paymentMethodId` to charge the customer.

## Plugin Internals

The plugin generates a token for the client by means of a [servlet](https://github.com/killbill/killbill-braintree/blob/f71ecc98ee6924aa216aa10200027d21640b50f0/src/main/java/org/killbill/billing/plugin/braintree/core/resources/BraintreeTokenServlet.java). The client uses this token to send payment information to Braintree in exchange for a nonce. The nonce is used by the plugin to create a payment method in Kill Bill. Refer to the [Braintree documentation](https://developer.paypal.com/braintree/docs/start/overview) to know more.

Payment methods that are supported include:

* Credit Card (with or without 3D Secure)
* PayPal
* ACH

Note that most of the differences in processing these payment methods are managed on the client side, and the nonce received by the backend is handled in the same manner, with only some small differences.

## Integration

In order to use the Braintree plugin, follow the steps given below:

1. Ensure that the plugin is installed and configured as explained above.

2. Invoke the `BraintreeTokenServlet` to obtain a token:

```
curl -v \
    -u admin:password \
    -H "X-Killbill-ApiKey: bob" \
    -H "X-Killbill-ApiSecret: lazar" \
    -H "Content-Type: application/json" \
    -H "Accept: application/json" \
    -H "X-Killbill-CreatedBy: demo" \
    -H "X-Killbill-Reason: demo" \
    -H "X-Killbill-Comment: demo" \
    "http://localhost:8080/plugins/killbill-braintree/clientToken" 
```

3. Use the [Braintree Drop-in](https://developer.paypal.com/braintree/docs/start/drop-in) implementation to gather the customer's payment details and obtain a payment nonce. 

4. [Create a customer](https://developer.paypal.com/braintree/docs/guides/customers) in Braintree. Save the **Customer ID** generated for future reference (it should be something like **620594365**). 
  
5. [Create](https://killbill.github.io/slate/?shell#account-create-an-account) a Kill Bill account. Save the **accountId** for further use.

6. Create a payment method in Kill Bill using the nonce and the braintree customer id as follows:

```
curl -v \
     -u admin:password \
     -H "X-Killbill-ApiKey: bob" \
     -H "X-Killbill-ApiSecret: lazar" \
     -H "Content-Type: application/json" \
     -H "X-Killbill-CreatedBy: demo" \
     -X POST \
     --data-binary '{
       "pluginName": "killbill-braintree",
       "pluginInfo": {
         "properties": [
           {
             "key": "bt_nonce",
             "value": "xxx"
           },
           {
             "key": "bt_customer_id",
             "value": "xxx"
           }
         ]
       }
     }' \
     "http://127.0.0.1:8080/1.0/kb/accounts/<ACCOUNT_ID>/paymentMethods?isDefault=true"
```

7. Use the `paymentMethodId` to charge the customer.

A full end-to-end integration demo that demonstrates Braintree integration is available [here](https://github.com/killbill/killbill-braintree-demo).


## Payment methods sync

If you are already storing payment methods in Braintree (or if you want to migrate from another billing system and already have customers in Braintree), the flow to set up Kill Bill accounts is as follows:

1. [Create](https://killbill.github.io/slate/?shell#account-create-an-account) a Kill Bill account.

2. Attach the custom field `BRAINTREE_CUSTOMER_ID` to the Kill Bill account. The custom field value should be the Braintree customer id:

```bash
curl -v \
    -X POST \
    -u admin:password \
    -H "X-Killbill-ApiKey: bob" \
    -H "X-Killbill-ApiSecret: lazar" \
    -H "Content-Type: application/json" \
    -H "Accept: application/json" \
    -H "X-Killbill-CreatedBy: demo" \
    -H "X-Killbill-Reason: demo" \
    -H "X-Killbill-Comment: demo" \
    -d '[ { "objectType": "ACCOUNT", "name": "BRAINTREE_CUSTOMER_ID", "value": "<braintreeCustomerId>" }]' \
    "http://127.0.0.1:8080/1.0/kb/accounts/<accountId>/customFields"
```

3. Sync the payment methods from Braintree to Kill Bill:

```bash
curl -v \
     -X PUT \
     -u admin:password \
     -H "X-Killbill-ApiKey: bob" \
     -H "X-Killbill-ApiSecret: lazar" \
     -H "Content-Type: application/json" \
     -H "Accept: application/json" \
     -H "X-Killbill-CreatedBy: demo" \
     -H "X-Killbill-Reason: demo" \
     -H "X-Killbill-Comment: demo" \
     "http://127.0.0.1:8080/1.0/kb/accounts/<ACCOUNT_ID>/paymentMethods/refresh"
```



