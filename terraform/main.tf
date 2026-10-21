terraform {
  required_version = ">= 1.6"

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
  description = "Digest (sha256:...) of the image pushed to the ECR repository. While null, only ECR and the IAM role are created."
  type        = string
  default     = null

  validation {
    condition     = var.image_digest == null || can(regex("^sha256:[0-9a-f]{64}$", var.image_digest))
    error_message = "image_digest must look like sha256:<64 hex characters>."
  }
}

provider "aws" {
  region = var.region
}

resource "aws_ecr_repository" "app" {
  name                 = var.name_prefix
  image_tag_mutability = "IMMUTABLE"
  # Lets `terraform destroy` delete the repository together with its images.
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

  depends_on = [aws_iam_role_policy.logging, aws_cloudwatch_log_group.app]
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
