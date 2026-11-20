terraform {
  required_version = ">= 1.13.2"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.69"
    }
  }
}

variable "region" {
  description = "AWS region to deploy to."
  type        = string
  default     = "eu-west-1"
}

variable "name_prefix" {
  description = "Prefix for all resource names (ECR repository, IAM role, Lambda function, log group)."
  type        = string
  default     = "sharaf-demo"
}

variable "image_digest" {
  description = "Digest (sha256:...) of the image pushed to the ECR repository. While null, the function and its URL are omitted."
  type        = string
  default     = null

  validation {
    condition     = var.image_digest == null || can(regex("^sha256:[0-9a-f]{64}$", var.image_digest))
    error_message = "image_digest must look like sha256:<64 hex characters>."
  }
}

locals {
  use_floci      = terraform.workspace == "floci"
  floci_endpoint = "http://127.0.0.1:4566"
}

provider "aws" {
  region                      = local.use_floci ? "us-east-1" : var.region
  access_key                  = local.use_floci ? "test" : null
  secret_key                  = local.use_floci ? "test" : null
  skip_credentials_validation = local.use_floci
  skip_metadata_api_check     = local.use_floci
  skip_requesting_account_id  = local.use_floci

  dynamic "endpoints" {
    for_each = local.use_floci ? [local.floci_endpoint] : []
    content {
      cloudwatchlogs = endpoints.value
      dynamodb       = endpoints.value
      ecr            = endpoints.value
      iam            = endpoints.value
      lambda         = endpoints.value
      sts            = endpoints.value
    }
  }
}

resource "aws_ecr_repository" "app" {
  name                 = var.name_prefix
  image_tag_mutability = "IMMUTABLE"
  # Lets `tofu destroy` delete the repository together with its images.
  force_delete = true
}

resource "aws_cloudwatch_log_group" "app" {
  name              = "/aws/lambda/${var.name_prefix}"
  retention_in_days = 7
}

data "aws_iam_policy_document" "assume_lambda" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

data "aws_iam_policy_document" "logging" {
  statement {
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.app.arn}:*"]
  }
}

resource "aws_iam_role" "lambda" {
  name               = "${var.name_prefix}-lambda"
  assume_role_policy = data.aws_iam_policy_document.assume_lambda.json
}

resource "aws_iam_role_policy" "logging" {
  name   = "cloudwatch-logs"
  role   = aws_iam_role.lambda.id
  policy = data.aws_iam_policy_document.logging.json
}

resource "aws_dynamodb_table" "greetings" {
  name           = "sharaf-demo-greetings"
  billing_mode   = "PROVISIONED"
  table_class    = "STANDARD"
  read_capacity  = 1
  write_capacity = 1
  hash_key       = "id"

  attribute {
    name = "id"
    type = "S"
  }
}

data "aws_iam_policy_document" "greetings" {
  statement {
    actions   = ["dynamodb:GetItem", "dynamodb:PutItem"]
    resources = [aws_dynamodb_table.greetings.arn]
  }
}

resource "aws_iam_role_policy" "greetings" {
  name   = "greetings"
  role   = aws_iam_role.lambda.id
  policy = data.aws_iam_policy_document.greetings.json
}

# Created only after an image has been pushed (see README for the two-step first deployment).
resource "aws_lambda_function" "app" {
  count = var.image_digest == null ? 0 : 1

  function_name = var.name_prefix
  role          = aws_iam_role.lambda.arn
  package_type  = "Image"
  image_uri     = "${aws_ecr_repository.app.repository_url}@${var.image_digest}"
  architectures = ["x86_64"]
  memory_size   = 1024
  timeout       = 30

  dynamic "environment" {
    for_each = local.use_floci ? [1] : []
    content {
      variables = {
        AWS_ENDPOINT_URL_DYNAMODB = "http://floci:4566"
      }
    }
  }

  # Floci reads back empty image settings for images that use their Dockerfile defaults.
  dynamic "image_config" {
    for_each = local.use_floci ? [1] : []
    content {
      command     = []
      entry_point = []
    }
  }

  depends_on = [aws_iam_role_policy.logging, aws_iam_role_policy.greetings, aws_cloudwatch_log_group.app]
}

resource "aws_lambda_function_url" "app" {
  count = var.image_digest == null ? 0 : 1

  function_name      = aws_lambda_function.app[0].function_name
  authorization_type = "NONE" # PUBLIC, unauthenticated endpoint. Demo only!
}

# Since October 2025 a public Function URL needs BOTH permissions below.
resource "aws_lambda_permission" "url_invoke_function_url" {
  count = var.image_digest == null ? 0 : 1

  statement_id           = "AllowPublicInvokeFunctionUrl"
  action                 = "lambda:InvokeFunctionUrl"
  function_name          = aws_lambda_function.app[0].function_name
  principal              = "*"
  function_url_auth_type = "NONE"
}

resource "aws_lambda_permission" "url_invoke_function" {
  count = var.image_digest == null ? 0 : 1

  statement_id             = "AllowPublicInvokeFunctionViaUrl"
  action                   = "lambda:InvokeFunction"
  function_name            = aws_lambda_function.app[0].function_name
  principal                = "*"
  invoked_via_function_url = true
}

output "ecr_repository_url" {
  value = aws_ecr_repository.app.repository_url
}

output "function_url" {
  description = "Public Function URL (null until image_digest is set)."
  value       = one(aws_lambda_function_url.app[*].function_url)
}

output "greetings_table_name" {
  value = aws_dynamodb_table.greetings.name
}
