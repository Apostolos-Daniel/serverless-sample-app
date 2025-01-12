# Java Implementation

This README contains relevant instructions for deploying the sample application with each of the available IaC tools. As well as details on any Node specific implementation details when instrumenting with Datadog.

## Testing

To generate load against your application, see the documentation on running a [load test](../../README.md#load-tests)

## AWS CDK

When using Java as your language of choice with the AWS CDK, you need to manually configure the Datadog Lambda Extension and the `dd-trace-java` layer. To simplify this configuration, a custom [`InstrumentFunction`](./cdk/src/main/java/com/cdk/constructs/InstrumentedFunction.java) construct is used to centralise all of the configuration.

```java
List<ILayerVersion> layers = new ArrayList<>(2);
layers.add(LayerVersion.fromLayerVersionArn(this, "DatadogJavaLayer", "arn:aws:lambda:eu-west-1:464622532012:layer:dd-trace-java:15"));
layers.add(LayerVersion.fromLayerVersionArn(this, "DatadogLambdaExtension", "arn:aws:lambda:eu-west-1:464622532012:layer:Datadog-Extension:63"));

var builder = Function.Builder.create(this, props.routingExpression())
    // Remove for brevity
    .layers(layers);
```

The relevant Datadog environment variables are also set.

```java
Map<String, String> lambdaEnvironment = new HashMap<>();
lambdaEnvironment.put("AWS_LAMBDA_EXEC_WRAPPER", "/opt/datadog_wrapper");
lambdaEnvironment.put("DD_SITE", "datadoghq.eu");
lambdaEnvironment.put("DD_SERVICE", props.sharedProps().service());
lambdaEnvironment.put("DD_ENV", props.sharedProps().env());
lambdaEnvironment.put("DD_VERSION", props.sharedProps().version());
lambdaEnvironment.put("DD_API_KEY_SECRET_ARN", props.sharedProps().ddApiKeySecret().getSecretArn());
lambdaEnvironment.put("DD_CAPTURE_LAMBDA_PAYLOAD", "true");
lambdaEnvironment.put("DD_LOGS_INJECTION", "true");
```

The Datadog extension retrieves your Datadog API key from a Secrets Manager secret. For this to work, ensure you create a secret in your account containing your API key and set the `DD_SECRET_ARN` environment variable before deployment.. Eensure that you give your Lambda function permission to access the AWS Secrets Manager secret

```java
props.sharedProps().ddApiKeySecret().grantRead(this.function);
```

### Deploy

To simplify deployment, all of the different microservices are managed in the same CDK project. This **is not recommended** in real applications, but simplifies the deployment for demonstration purposes.

Each microservice is implemented as a seperate CloudFormation Stack, and there are no direct dependencies between stacks. Each stack stores relevant resource ARN's (SNS Topic ARN etc) in SSM Parameter Store, and the other stacks dynamically load the ARN's:

```java
String productCreatedArn = StringParameter.valueForStringParameter(this, "/java/product-api/product-created-topic");
ITopic productCreatedTopic = Topic.fromTopicArn(this, "ProductCreatedTopic", productCreatedArn);
```

You first need to compile your Java application code, before running `cdk deploy`. Run the below commands in order to deploy.

```sh
export DD_SECRET_ARN=<YOUR SECRET ARN>
mvn clean package
cd cdk
cdk deploy --all --require-approval never
```

If you wish to deploy individual stacks, you can do that by running the respective command below:

```sh
cdk deploy JavaSharedStack --require-approval never
cdk deploy JavaProductApiStack --require-approval never
cdk deploy JavaEventPublisherStack --require-approval never
cdk deploy JavaProductPricingService --require-approval never
```

### Cleanup

To cleanup resources run

```sh
cdk destroy --all
```

## AWS SAM

The AWS SAM example leverages the Datadog CloudFormation Macro. The macro auto-instruments your Lambda functions at the point of deployment. Ensure you have followed the [installation instructions](https://docs.datadoghq.com/serverless/libraries_integrations/macro/) before continuing with the SAM deployment.

Ensure you have set the below environment variables before starting deployment:

- `DD_SECRET_ARN`: The Secrets Manager Secret ARN holding your Datadog API Key
- `AWS_REGION`: The AWS region you want to deploy to

Once both environment variables are set, use the below `sh` script to deploy all backend services. You can deploy individual services as well if required. Due to the SSM parameters holding SNS Topic ARN's, the order of deployment is important.

### Deploy

The `template.yaml` file contains an example of using a nested stack to deploy all 6 backend services in a single command. This **is not** recommended for production use cases, instead preferring independent deployments. For the purposes of this demonstration, a single template makes test deployments easier.

```sh
sam build
sam deploy --stack-name NodeTracing --parameter-overrides ParameterKey=DDApiKeySecretArn,ParameterValue="$DD_SECRET_ARN" --resolve-s3 --capabilities CAPABILITY_IAM CAPABILITY_AUTO_EXPAND --region $AWS_REGION
```

To deploy individual stacks and resources, you can use the below commands.

```sh
# Deploy Shared Resoures
sam build -t template-shared.yaml &&
sam deploy --stack-name NodeSharedStack --resolve-s3 --capabilities CAPABILITY_IAM --region $AWS_REGION &&

# Deploy API
sam build -t template-api.yaml &&
sam deploy --stack-name NodeProductApiStack --parameter-overrides ParameterKey=DDApiKeySecretArn,ParameterValue="$DD_SECRET_ARN" --resolve-s3 --capabilities CAPABILITY_IAM --region $AWS_REGION &&

# Deploy Pricing Service
sam build -t template-pricing-service.yaml &&
sam deploy --stack-name NodeProductPricingServiceStack --parameter-overrides ParameterKey=DDApiKeySecretArn,ParameterValue="$DD_SECRET_ARN" --resolve-s3 --capabilities CAPABILITY_IAM --region $AWS_REGION &&

# Deploy Public Event Publisher
sam build -t template-product-event-publisher.yaml &&
sam deploy --stack-name NodeProductPublicEventPublisherStack --parameter-overrides ParameterKey=DDApiKeySecretArn,ParameterValue="$DD_SECRET_ARN" --resolve-s3 --capabilities CAPABILITY_IAM --region $AWS_REGION &&

# Deploy Product API worker
sam build -t template-product-api-worker.yaml &&
sam deploy --stack-name NodeProductApiWorkerStack --parameter-overrides ParameterKey=DDApiKeySecretArn,ParameterValue="$DD_SECRET_ARN" --resolve-s3 --capabilities CAPABILITY_IAM --region $AWS_REGION &&

# Deploy Inventory ACL
sam build -t template-inventory-acl.yaml &&
sam deploy --stack-name NodeInventoryAcl --parameter-overrides ParameterKey=DDApiKeySecretArn,ParameterValue="$DD_SECRET_ARN" --resolve-s3 --capabilities CAPABILITY_IAM --region $AWS_REGION &&

# Deploy Inventory Ordering Service
sam build -t template-inventory-ordering-service.yaml &&
sam deploy --stack-name NodeInventoryOrderingService --parameter-overrides ParameterKey=DDApiKeySecretArn,ParameterValue="$DD_SECRET_ARN" --resolve-s3 --capabilities CAPABILITY_IAM --region $AWS_REGION

# Deploy Analytics Backend
sam build -t template-analytics-service.yaml &&
sam deploy --stack-name NodeAnalyticsService --parameter-overrides ParameterKey=DDApiKeySecretArn,ParameterValue="$DD_SECRET_ARN" --resolve-s3 --capabilities CAPABILITY_IAM --region $AWS_REGION
```

### Cleanup

Use the below `sh` script to cleanup resources deployed with AWS SAM.

```sh
sam delete --stack-name NodeInventoryOrderingService --region $AWS_REGION --no-prompts &&
sam delete --stack-name NodeInventoryAcl --region $AWS_REGION --no-prompts &&
sam delete --stack-name NodeProductApiWorkerStack --region $AWS_REGION --no-prompts &&
sam delete --stack-name NodeProductPublicEventPublisherStack --region $AWS_REGION --no-prompts &&
sam delete --stack-name NodeProductPricingServiceStack --region $AWS_REGION --no-prompts &&
sam delete --stack-name NodeProductApiStack --region $AWS_REGION --no-prompts &&
sam delete --stack-name NodeSharedStack --region $AWS_REGION --no-prompts
```

## Terraform

Terraform does not natively support transpiling Typescript into JS code. When deploying with Typescript, you first need to transpile and ZIP up the typescript code. The [`deploy.sh`](./deploy.sh) script performs this action. Iterating over all of the `build*.js` files and running esbuild before zipping up all folders in the output folder.

### Configuration

A customer [`lambda_function`](./infra/modules/lambda-function/main.tf) module is used to group together all the functionality for deploying Lambda functions. This handles the creation of the CloudWatch Log Groups, and default IAM roles.

The Datadog Lambda Terraform module is used to create and configure the Lambda function with the required extensions, layers and configurations.

> **IMPORTANT!** If you are using AWS Secrets Manager to hold your Datadog API key, ensure your Lambda function has permissions to call the `secretsmanager:GetSecretValue` IAM action.

```terraform
module "aws_lambda_function" {
  source  = "DataDog/lambda-datadog/aws"
  version = "1.3.0"

  filename                 = var.zip_file
  function_name            = var.function_name
  role                     = aws_iam_role.lambda_function_role.arn
  handler                  = var.lambda_handler
  runtime                  = "nodejs20.x"
  memory_size              = 512
  logging_config_log_group = aws_cloudwatch_log_group.lambda_log_group.name
  source_code_hash = "${filebase64sha256(var.zip_file)}"
  timeout = 29

  environment_variables = merge(tomap({
    "DD_API_KEY_SECRET_ARN" : var.dd_api_key_secret_arn
    "DD_EXTENSION_VERSION": "next"
    "DD_ENV" : var.env
    "DD_SERVICE" : var.service_name
    "DD_SITE" : "datadoghq.eu"
    "DD_VERSION" : var.app_version
    "ENV": var.env
    "POWERTOOLS_SERVICE_NAME": var.service_name
    "POWERTOOLS_LOG_LEVEL": "INFO" }),
    var.environment_variables
  )

  datadog_extension_layer_version = 62
  datadog_node_layer_version      = 112
}
```

### Deploy

To deploy, first create a file named `infra/dev.tfvars`. In your tfvars file, you need to add your the AWS Secrets Manager ARN for the secret containing your Datadog API Key.

```tf
dd_api_key_secret_arn="<DD_SECRET_ARN>"
```

There's a single `main.tf` that contains all 7 backend services as modules. This is **not** recommended in production, and you should deploy backend services independenly. However, to simplify this demo deployment a single file is used.

The root of the repository contains a `deploy.sh` file, this will compile all your Java code, generate the ZIP files and run `terraform apply`. To deploy the Terraform example, simply run:

```sh
./deploy.sh
```

### Cleanup

To cleanup all Terraform resources run:

```sh
cd infra
terraform destroy --var-file dev.tfvars
```

## Serverless Framework

Datadog provides a [plugin](https://www.serverless.com/plugins/serverless-plugin-datadog) to simply configuration of your serverless applications when using the [serverless framework](https://www.serverless.com/). Inside your `serverless.yml` add a `custom.datadog` block. The available configuration options are available in the [documentation](https://www.serverless.com/plugins/serverless-plugin-datadog#configuration-parameters).

> **IMPORTANT** Ensure you add permissions to `secretsmanager:GetSecretValue` for the Secrets Manager secret holding your Datadog API key

```yaml
custom:
  datadog:
    apiKeySecretArn: ${param:DD_SECRET_ARN}
    site: datadoghq.eu
    env: ${sls:stage}
    service: ${self:custom.serviceName}
    version: latest
    # Use this property with care in production to ensure PII/Sensitive data is not stored in Datadog
    captureLambdaPayload: true
    propagateUpstreamTrace: true
```

### Deploy

Ensure you have set the below environment variables before starting deployment:

- `DD_SECRET_ARN`: The Secrets Manager Secret ARN holding your Datadog API Key
- `AWS_REGION`: The AWS region you want to deploy to

Once set, use the below commands to deploy each of the individual backend services on by one.

```sh
serverless deploy --stage dev --region=${AWS_REGION} --config serverless-shared.yml &&
serverless deploy --param="DD_SECRET_ARN=${DD_SECRET_ARN}" --stage dev --region=${AWS_REGION} --config serverless-api.yml &&
serverless deploy --param="DD_SECRET_ARN=${DD_SECRET_ARN}" --stage dev --region=${AWS_REGION} --config serverless-pricing-service.yml &&
serverless deploy --param="DD_SECRET_ARN=${DD_SECRET_ARN}" --stage dev --region=${AWS_REGION} --config serverless-api-worker.yml &&
serverless deploy --param="DD_SECRET_ARN=${DD_SECRET_ARN}" --stage dev --region=${AWS_REGION} --config serverless-product-event-publisher.yml &&
serverless deploy --param="DD_SECRET_ARN=${DD_SECRET_ARN}" --stage dev --region=${AWS_REGION} --config serverless-inventory-acl.yml &&
serverless deploy --param="DD_SECRET_ARN=${DD_SECRET_ARN}" --stage dev --region=${AWS_REGION} --config serverless-inventory-ordering-service.yml &&
serverless deploy --param="DD_SECRET_ARN=${DD_SECRET_ARN}" --stage dev --region=${AWS_REGION} --config serverless-analytics-service.yml
```

### Cleanup

The same commands can be used to cleanup all resources, but replacing `deploy` with `remove`.

```sh
serverless remove --param="DD_SECRET_ARN=${DD_SECRET_ARN}" --stage dev --region=${AWS_REGION} --config serverless-analytics-service.yml &&
serverless remove --param="DD_SECRET_ARN=${DD_SECRET_ARN}" --stage dev --region=${AWS_REGION} --config serverless-inventory-ordering-service.yml &&
serverless remove --param="DD_SECRET_ARN=${DD_SECRET_ARN}" --stage dev --region=${AWS_REGION} --config serverless-inventory-acl.yml &&
serverless remove --param="DD_SECRET_ARN=${DD_SECRET_ARN}" --stage dev --region=${AWS_REGION} --config serverless-product-event-publisher.yml &&
serverless remove --param="DD_SECRET_ARN=${DD_SECRET_ARN}" --stage dev --region=${AWS_REGION} --config serverless-api-worker.yml &&
serverless remove --param="DD_SECRET_ARN=${DD_SECRET_ARN}" --stage dev --region=${AWS_REGION} --config serverless-pricing-service.yml &&
serverless remove --param="DD_SECRET_ARN=${DD_SECRET_ARN}" --stage dev --region=${AWS_REGION} --config serverless-api.yml &&
serverless remove --stage dev --region=${AWS_REGION} --config serverless-shared.yml
```