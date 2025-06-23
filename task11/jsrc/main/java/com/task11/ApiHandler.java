package com.task11;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.Gson;
import com.syndicate.deployment.annotations.resources.DependsOn;
import com.syndicate.deployment.annotations.environment.EnvironmentVariable;
import com.syndicate.deployment.annotations.environment.EnvironmentVariables;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.model.ResourceType;
import com.syndicate.deployment.model.RetentionSetting;
import com.syndicate.deployment.model.environment.ValueTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;

import java.util.*;
import java.util.stream.Collectors;


@LambdaHandler(
		lambdaName = "api_handler",
		roleName = "api_handler-role",
		isPublishVersion = true,
		aliasName = "${lambdas_alias_name}",
		logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED
)
@DependsOn(
		name = "simple-booking-userpool",
		resourceType = ResourceType.COGNITO_USER_POOL
)
@DependsOn(
		name = "Tables",
		resourceType = ResourceType.DYNAMODB_TABLE
)
@DependsOn(
		name = "Reservations",
		resourceType = ResourceType.DYNAMODB_TABLE
)
@EnvironmentVariables(value = {
		@EnvironmentVariable(key = "tables_table", value = "${tables_table}"),
		@EnvironmentVariable(key = "reservations_table", value = "${reservations_table}"),
		@EnvironmentVariable(key = "booking_userpool_id", value = "simple-booking-userpool", valueTransformer = ValueTransformer.USER_POOL_NAME_TO_USER_POOL_ID),
		@EnvironmentVariable(key = "booking_userpool_client_id", value = "simple-booking-userpool", valueTransformer = ValueTransformer.USER_POOL_NAME_TO_CLIENT_ID)
})
public class ApiHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

	private final Gson gson = new Gson();
	private final DynamoDbClient dynamoDbClient;
	private final CognitoIdentityProviderClient cognitoClient;

	private final String tablesTableName;
	private final String reservationsTableName;
	private final String userPoolId;
	private final String userPoolClientId;

	public ApiHandler() {
		// Initialize AWS SDK clients
		this.dynamoDbClient = DynamoDbClient.builder()
				.region(Region.EU_WEST_1)
				.build();
		this.cognitoClient = CognitoIdentityProviderClient.builder()
				.region(Region.EU_WEST_1)
				.build();

		// Get resource names from environment variables
		this.tablesTableName = System.getenv("tables_table");
		this.reservationsTableName = System.getenv("reservations_table");
		this.userPoolId = System.getenv("booking_userpool_id");
		this.userPoolClientId = System.getenv("booking_userpool_client_id");
	}

	@Override
	public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
		try {
			String resource = request.getResource();
			String httpMethod = request.getHttpMethod();

			switch (resource) {
				case "/signup":
					if ("POST".equals(httpMethod)) return handleSignUp(request);
					break;
				case "/signin":
					if ("POST".equals(httpMethod)) return handleSignIn(request);
					break;
				case "/tables":
					if ("GET".equals(httpMethod)) return handleGetTables();
					if ("POST".equals(httpMethod)) return handlePostTable(request);
					break;
				case "/tables/{tableId}":
					if ("GET".equals(httpMethod)) return handleGetTableById(request);
					break;
				case "/reservations":
					if ("GET".equals(httpMethod)) return handleGetReservations();
					if ("POST".equals(httpMethod)) return handlePostReservation(request);
					break;
			}
			return buildResponse(404, "{\"error\":\"Not Found\"}");
		} catch (Exception e) {
			context.getLogger().log("Error: " + e.getMessage());
			return buildResponse(400, "{\"error\":\"Bad Request\"}");
		}
	}

	private APIGatewayProxyResponseEvent handleSignUp(APIGatewayProxyRequestEvent request) {
		Map<String, String> body = gson.fromJson(request.getBody(), Map.class);
		String email = body.get("email");
		String password = body.get("password");

		AdminCreateUserRequest userRequest = AdminCreateUserRequest.builder()
				.userPoolId(userPoolId)
				.username(email)
				.userAttributes(
						AttributeType.builder().name("email").value(email).build(),
						AttributeType.builder().name("given_name").value(body.get("firstName")).build(),
						AttributeType.builder().name("family_name").value(body.get("lastName")).build(),
						AttributeType.builder().name("email_verified").value("true").build()
				)
				.messageAction(MessageActionType.SUPPRESS)
				.build();
		cognitoClient.adminCreateUser(userRequest);

		AdminSetUserPasswordRequest passwordRequest = AdminSetUserPasswordRequest.builder()
				.userPoolId(userPoolId)
				.username(email)
				.password(password)
				.permanent(true)
				.build();
		cognitoClient.adminSetUserPassword(passwordRequest);

		return buildResponse(200, null);
	}

	private APIGatewayProxyResponseEvent handleSignIn(APIGatewayProxyRequestEvent request) {
		Map<String, String> body = gson.fromJson(request.getBody(), Map.class);

		Map<String, String> authParams = new HashMap<>();
		authParams.put("USERNAME", body.get("email"));
		authParams.put("PASSWORD", body.get("password"));

		InitiateAuthRequest authRequest = InitiateAuthRequest.builder()
				.clientId(userPoolClientId)
				.authFlow(AuthFlowType.USER_PASSWORD_AUTH)
				.authParameters(authParams)
				.build();

		InitiateAuthResponse authResponse = cognitoClient.initiateAuth(authRequest);
		String idToken = authResponse.authenticationResult().idToken();

		return buildResponse(200, String.format("{\"idToken\":\"%s\"}", idToken));
	}

	private APIGatewayProxyResponseEvent handleGetTables() {
		ScanRequest scanRequest = ScanRequest.builder().tableName(tablesTableName).build();
		List<Map<String, Object>> tables = dynamoDbClient.scan(scanRequest).items().stream()
				.map(this::convertDynamoDbItemToTableMap)
				.collect(Collectors.toList());
		return buildResponse(200, gson.toJson(Map.of("tables", tables)));
	}

	private APIGatewayProxyResponseEvent handlePostTable(APIGatewayProxyRequestEvent request) {
		Map<String, Object> body = gson.fromJson(request.getBody(), Map.class);
		Map<String, AttributeValue> item = new HashMap<>();
		item.put("id", AttributeValue.builder().n(String.valueOf(((Double)body.get("id")).intValue())).build());
		item.put("number", AttributeValue.builder().n(String.valueOf(((Double)body.get("number")).intValue())).build());
		item.put("places", AttributeValue.builder().n(String.valueOf(((Double)body.get("places")).intValue())).build());
		item.put("isVip", AttributeValue.builder().bool((Boolean) body.get("isVip")).build());
		if (body.containsKey("minOrder")) {
			item.put("minOrder", AttributeValue.builder().n(String.valueOf(((Double)body.get("minOrder")).intValue())).build());
		}

		dynamoDbClient.putItem(PutItemRequest.builder().tableName(tablesTableName).item(item).build());
		return buildResponse(200, gson.toJson(Map.of("id", ((Double)body.get("id")).intValue())));
	}

	private APIGatewayProxyResponseEvent handleGetTableById(APIGatewayProxyRequestEvent request) {
		String tableId = request.getPathParameters().get("tableId");
		GetItemRequest getRequest = GetItemRequest.builder()
				.tableName(tablesTableName)
				.key(Map.of("id", AttributeValue.builder().n(tableId).build()))
				.build();

		Map<String, AttributeValue> item = dynamoDbClient.getItem(getRequest).item();
		if (item == null || item.isEmpty()) {
			return buildResponse(400, "{\"error\":\"Table not found\"}");
		}
		return buildResponse(200, gson.toJson(convertDynamoDbItemToTableMap(item)));
	}

	private APIGatewayProxyResponseEvent handleGetReservations() {
		ScanRequest scanRequest = ScanRequest.builder().tableName(reservationsTableName).build();
		List<Map<String, Object>> reservations = dynamoDbClient.scan(scanRequest).items().stream()
				.map(this::convertDynamoDbItemToReservationMap)
				.collect(Collectors.toList());
		return buildResponse(200, gson.toJson(Map.of("reservations", reservations)));
	}

	private APIGatewayProxyResponseEvent handlePostReservation(APIGatewayProxyRequestEvent request) {
		Map<String, Object> body = gson.fromJson(request.getBody(), Map.class);

		String reservationId = UUID.randomUUID().toString();
		Map<String, AttributeValue> item = new HashMap<>();
		item.put("reservationId", AttributeValue.builder().s(reservationId).build());
		item.put("tableNumber", AttributeValue.builder().n(String.valueOf(((Double)body.get("tableNumber")).intValue())).build());
		item.put("clientName", AttributeValue.builder().s(body.get("clientName").toString()).build());
		item.put("phoneNumber", AttributeValue.builder().s(body.get("phoneNumber").toString()).build());
		item.put("date", AttributeValue.builder().s(body.get("date").toString()).build());
		item.put("slotTimeStart", AttributeValue.builder().s(body.get("slotTimeStart").toString()).build());
		item.put("slotTimeEnd", AttributeValue.builder().s(body.get("slotTimeEnd").toString()).build());

		dynamoDbClient.putItem(PutItemRequest.builder().tableName(reservationsTableName).item(item).build());
		return buildResponse(200, gson.toJson(Map.of("reservationId", reservationId)));
	}

	// Helper Methods
	private Map<String, Object> convertDynamoDbItemToTableMap(Map<String, AttributeValue> item) {
		Map<String, Object> table = new HashMap<>();
		table.put("id", Integer.parseInt(item.get("id").n()));
		table.put("number", Integer.parseInt(item.get("number").n()));
		table.put("places", Integer.parseInt(item.get("places").n()));
		table.put("isVip", item.get("isVip").bool());
		if (item.containsKey("minOrder")) {
			table.put("minOrder", Integer.parseInt(item.get("minOrder").n()));
		}
		return table;
	}

	private Map<String, Object> convertDynamoDbItemToReservationMap(Map<String, AttributeValue> item) {
		Map<String, Object> reservation = new HashMap<>();
		reservation.put("tableNumber", Integer.parseInt(item.get("tableNumber").n()));
		reservation.put("clientName", item.get("clientName").s());
		reservation.put("phoneNumber", item.get("phoneNumber").s());
		reservation.put("date", item.get("date").s());
		reservation.put("slotTimeStart", item.get("slotTimeStart").s());
		reservation.put("slotTimeEnd", item.get("slotTimeEnd").s());
		return reservation;
	}

	private APIGatewayProxyResponseEvent buildResponse(int statusCode, String body) {
		APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
		response.setStatusCode(statusCode);
		response.setHeaders(Map.of("Content-Type", "application/json", "Access-Control-Allow-Origin", "*", "Access-Control-Allow-Methods", "*", "Access-Control-Allow-Headers", "*"));
		if (body != null) {
			response.setBody(body);
		}
		return response;
	}
}