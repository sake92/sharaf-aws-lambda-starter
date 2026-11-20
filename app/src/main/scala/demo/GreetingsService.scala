package demo

import java.util.UUID
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.{AttributeValue, GetItemRequest, PutItemRequest}

private[demo] final class GreetingsService(dynamo: DynamoDbClient):
  private val greetingsTable = "sharaf-demo-greetings"

  def findGreeting(id: String): Option[HelloResponse] =
    val request = GetItemRequest
      .builder()
      .tableName(greetingsTable)
      .key(java.util.Map.of("id", AttributeValue.fromS(id)))
      .consistentRead(true)
      .build()
    val result = dynamo.getItem(request)
    if result.hasItem then Some(HelloResponse(result.item().get("greeting").s()))
    else None

  def createGreeting(greeting: String): CreatedGreeting =
    val id = UUID.randomUUID().toString
    val request = PutItemRequest
      .builder()
      .tableName(greetingsTable)
      .item(java.util.Map.of("id", AttributeValue.fromS(id), "greeting", AttributeValue.fromS(greeting)))
      .conditionExpression("attribute_not_exists(id)")
      .build()
    dynamo.putItem(request)
    CreatedGreeting(id, greeting)
