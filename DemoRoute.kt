package demo

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import org.apache.camel.Exchange
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.component.jackson.JacksonDataFormat
import org.apache.camel.component.kafka.KafkaConstants
import org.apache.camel.model.dataformat.JsonLibrary
import org.apache.camel.model.rest.RestBindingMode
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*

class Routes : RouteBuilder() {
  private val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/YYYY HH:mm:SS")
  private val df = JacksonDataFormat(LinkedHashMap::class.java)
  private val auditLogs: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

  override fun configure() {
    restConfiguration().bindingMode(RestBindingMode.json)

    rest("/temperature-measurements/v1/dummy")
            .post()
            .to("direct:handle-weather-data-posts")

    rest("/audit-log")
            .get()
            .to("direct:print-audit-log")

    rest("/*") //TODO can be removed with conditional policy in 3scale policy chain
            .get()
            .to("direct:wrap-audit-log-get")
            .post()
            .to("direct:wrap-audit-log-post")

    from("direct:handle-weather-data-posts")
            .log("direct:forwarding-get")
            .log("Incoming Body is \${body}")
            .log("Incoming Headers is \${headers}")
            .log("Incoming Headers.CamelRestHttpUri is \${header.CamelRestHttpUri}")
            .log("Incoming Headers.CamelHttpUri is \${header.CamelHttpUri}")
            .log("Incoming Headers.CamelHttpUrl is \${header.CamelHttpUrl}")
            .log("Incoming Headers.CamelHttpPath is \${header.CamelHttpPath}")
            .log("Incoming Headers.CamelHttpMethod is \${header.CamelHttpMethod}")
            .log("Incoming Headers.UserRole is \${header.x-user-roles}")
            .to("direct:write-to-amq-streams")

    from(kafkaDefinition)
            .log("Message received from Kafka : \${body}")
            .log("    on the topic \${headers[kafka.TOPIC]}")
            .log("    on the partition \${headers[kafka.PARTITION]}")
            .log("    with the offset \${headers[kafka.OFFSET]}")
            .log("    with the key \${headers[kafka.KEY]}")
            .delay(5000)
            .to("direct:weather-data-post")

    from("direct:write-to-amq-streams")
            .log("direct:write-to-amq-streams ==> to kafka")
            .setBody(constant("Message from Camel"))
            .setHeader(KafkaConstants.KEY, constant("Camel"))
            .to(kafkaDefinition)

    from("direct:weather-data-post")
            .log("direct:weather-data-post ==> from kafka")
            .setHeader("Accept", constant("application/json"))
            .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
//            .to("rest:post:/temperature-measurements/v1/dummy?host=weather-service-maarten-playground.apps.ocp4-bm.redhat.arrowlabs.be")
            .setHeader(Exchange.HTTP_METHOD, constant("POST"))
            //TODO fix following hard coded URL
            .toD("http://weather-service-maarten-playground.apps.ocp4-bm.redhat.arrowlabs.be/temperature-measurements/v1/dummy?bridgeEndpoint=true&maxDummyWritesPerMinute=75")
//            .to("rest:post:/temperature-measurements/v1/dummy?host=localhost:8083")


    from("direct:print-audit-log")
            .setBody()
            .constant(auditLogs)

    from("direct:wrap-audit-log-post")
            .multicast()
            .to("direct:auditLogging", "direct:forwarding-post")

    from("direct:wrap-audit-log-get")
            .multicast()
//            .to("direct:forwarding-get")
            .to("direct:auditLogging", "direct:forwarding-get")

    from("direct:auditLogging")
            .log("direct:auditLogging")
            .bean(this, "logUserAndAddUserRoles")

    from("direct:forwarding-get")
            .log("direct:forwarding-get")
            .log("Incoming Body is \${body}")
            .log("Incoming Headers is \${headers}")
            .log("Incoming Headers.CamelRestHttpUri is \${header.CamelRestHttpUri}")
            .log("Incoming Headers.CamelHttpUri is \${header.CamelHttpUri}")
            .log("Incoming Headers.CamelHttpUrl is \${header.CamelHttpUrl}")
            .log("Incoming Headers.CamelHttpPath is \${header.CamelHttpPath}")
            .log("Incoming Headers.CamelHttpMethod is \${header.CamelHttpMethod}")
            .log("Incoming Headers.CamelHttpQuery is \${header.CamelHttpQuery}")
            .log("Incoming Headers.UserRole is \${header.x-user-roles}")
            .bean(this, "logUserAndAddUserRoles")
            .log("Outgoing User Role Header is \${header.x-user-roles}")
//            .toD("http://weather-service-maarten-playground.apps.ocp4-bm.redhat.arrowlabs.be/temperature-measurements/v1/dummy/writes-count?bridgeEndpoint=true")
//            .toD("http://localhost:8083/temperature-measurements/v1/dummy/writes-count?bridgeEndpoint=true")
            .toD("rest:get:/?bridgeEndpoint=true&host=\${header.Host}&\${header.CamelHttpQuery}")
            .unmarshal()
            .json(JsonLibrary.Jackson)


    from("direct:forwarding-post")
            .log("direct:forwarding-post")
            .log("Incoming Body is \${body}")
            .log("Incoming Headers is \${headers}")
            .log("Incoming Headers.CamelRestHttpUri is \${header.CamelRestHttpUri}")
            .log("Incoming Headers.CamelHttpUri is \${header.CamelHttpUri}")
            .log("Incoming Headers.CamelHttpUrl is \${header.CamelHttpUrl}")
            .log("Incoming Headers.CamelHttpPath is \${header.CamelHttpPath}")
            .log("Incoming Headers.CamelHttpMethod is \${header.CamelHttpMethod}")
            .log("Incoming Headers.CamelHttpQuery is \${header.CamelHttpQuery}")
            .bean(this, "logUserAndAddUserRoles")
//            .bean(this, "transformMessage")
            .marshal(df)
            .log("Outgoing Headers.UserRole is \${header.x-user-roles}")
            .log("Outgoing pojo Body is \${body}")
//            .toD("http://person-service-maarten-playground.apps.ocp4-bm.redhat.arrowlabs.be/people/v2?bridgeEndpoint=true")
            .toD("rest:post:/?bridgeEndpoint=true&host=\${header.Host}&\${header.CamelHttpQuery}")
            .unmarshal()
            .json(JsonLibrary.Jackson)
  }

  //
  fun logUserAndAddUserRoles(exchange: Exchange) {
    if (exchange.`in`.getHeader("Authorization") != null) {
      val authorizationHeader = exchange.`in`.getHeader("Authorization").toString()
      val destinationUrl = exchange.`in`.getHeader("CamelHttpUri").toString()
      val destinationMethod = exchange.`in`.getHeader("CamelHttpMethod").toString()
      val accessToken = authorizationHeader.trim().replace("Bearer ", "")

      val kf: KeyFactory = KeyFactory.getInstance("RSA")
      val keySpecX509 = X509EncodedKeySpec(Base64.getDecoder().decode("MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEApQBAO+BQnneZAwb9DIXwzJ2MgD1KVvcFFpY14FSj+cvhl9gbrfImY54mEXBn7GcKWgFChD+HxLMP9aPVX662QWM74iucwlJPPVaJUDhj0zvtPWvpBW5iC6j+h4y27ZUaFWVVIFgkYPXTF3VbrNiAJQEx+0pV43P+wH1cPJVbJEBpryszILitQVVFn0bSn1hmbn03CL3wWgR47bKStwLwCbrmGxk8mnXMsnCQwU2ygWQBlMkFqs0kVnGtJglTv0xy9k5z/2xUp1HU00yrszhNS1tms2vUvpCU/vn6qDlIc5wFu4bFDTxzc/uY/wseNSjFkq5zulOBNZtS8jYMqMxaSwIDAQAB"))
      val publicKey = kf.generatePublic(keySpecX509)
      val algorithm = Algorithm.RSA256(publicKey as RSAPublicKey, null)
      val decodedJwt = JWT.decode(accessToken)
      algorithm.verify(decodedJwt)
      val email = decodedJwt.claims["email"]
      val roles = decodedJwt.getClaim("realm_access").asMap()["roles"]

      val auditLogLine = "${formatter.format(LocalDateTime.now())}| ${email}| ${destinationMethod} | ${destinationUrl} | ${roles}"
      auditLogs.add(auditLogLine)

      exchange.message.setHeader("x-user-roles", roles)
    }
  }

  fun addUserRoles(exchange: Exchange) {
    if (exchange.`in`.getHeader("Authorization") != null) {
      val authorizationHeader = exchange.`in`.getHeader("Authorization").toString()
      val accessToken = authorizationHeader.trim().replace("Bearer ", "")

      val kf: KeyFactory = KeyFactory.getInstance("RSA")
      val keySpecX509 = X509EncodedKeySpec(Base64.getDecoder().decode("MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEApQBAO+BQnneZAwb9DIXwzJ2MgD1KVvcFFpY14FSj+cvhl9gbrfImY54mEXBn7GcKWgFChD+HxLMP9aPVX662QWM74iucwlJPPVaJUDhj0zvtPWvpBW5iC6j+h4y27ZUaFWVVIFgkYPXTF3VbrNiAJQEx+0pV43P+wH1cPJVbJEBpryszILitQVVFn0bSn1hmbn03CL3wWgR47bKStwLwCbrmGxk8mnXMsnCQwU2ygWQBlMkFqs0kVnGtJglTv0xy9k5z/2xUp1HU00yrszhNS1tms2vUvpCU/vn6qDlIc5wFu4bFDTxzc/uY/wseNSjFkq5zulOBNZtS8jYMqMxaSwIDAQAB"))
      val publicKey = kf.generatePublic(keySpecX509)
      val algorithm = Algorithm.RSA256(publicKey as RSAPublicKey, null)
      val decodedJwt = JWT.decode(accessToken)
      algorithm.verify(decodedJwt)
      val roles = (decodedJwt.getClaim("realm_access").asMap()["roles"] as List<String>).joinToString(",")

      exchange.message.setHeader("x-user-roles", roles)
    }
  }

//  fun transformMessage(exchange: Exchange) {
//    val source: Message = exchange.getIn()
//    val json = source.getBody(MutableMap::class.java) as MutableMap<String, Any>
//    json.set("firstName", "modified-${json["firstName"]}")
//    source.setBody(json)
//  }

  companion object {
    val kafkaDefinition = "kafka:weather-data-topic" +
            "?brokers=my-cluster-kafka-listener1-bootstrap-maarten-playground.apps.ocp4-bm.redhat.arrowlabs.be:443" +
//            "&sslKeystoreLocation=/Users/mvandepe/Documents/software/kafka_2.13-3.3.1.redhat-00008/bin/client.truststore.jks" +
            "&sslKeystoreLocation=/opt/client.truststore.jks" +
            "&sslKeystorePassword=netraam" +
            "&securityProtocol=SSL"
  }
}
