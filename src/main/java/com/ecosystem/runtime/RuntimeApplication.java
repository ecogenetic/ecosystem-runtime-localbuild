package com.ecosystem.runtime;

import com.ecosystem.data.mongodb.ConnectionFactory;
import com.ecosystem.plugin.PluginLoader;
import com.ecosystem.runtime.continuous.*;
import com.ecosystem.utils.EnvironmentalVariables;
import com.ecosystem.utils.GlobalSettings;
import com.ecosystem.worker.license.ValidationService;
import com.mongodb.client.MongoClient;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.servers.Server;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import com.ecosystem.utils.log.LogManager;
import com.ecosystem.utils.log.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.cassandra.CassandraDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.cassandra.CassandraReactiveDataAutoConfiguration;

import org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.mongo.MongoRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoReactiveAutoConfiguration;

import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

import java.io.IOException;
import java.util.*;

import static com.ecosystem.worker.license.ValidationService.getEnvKey;
import static com.ecosystem.worker.license.ValidationService.setEnvKey;

/**
 * Core application definition
 *
 * @author ecosystem
 */
@OpenAPIDefinition(
		servers = {
				@Server(url = "/", description = "Default Server URL")
		})
@SpringBootApplication(exclude = {
		KafkaAutoConfiguration.class,
		CassandraDataAutoConfiguration.class,
		CassandraReactiveDataAutoConfiguration.class,
		MongoDataAutoConfiguration.class,
		MongoRepositoriesAutoConfiguration.class,
		MongoAutoConfiguration.class,
		MongoReactiveAutoConfiguration.class
})
@EnableWebSecurity
@Configuration
public class RuntimeApplication {
	private static ConfigurableApplicationContext context;

	private static final Logger LOGGER = LogManager.getLogger(RuntimeApplication.class.getName());
	public static String version;
	public static String ip = null;
	public static Set<String> whitelist = new HashSet<String>();
	private static Boolean securityFlag = false;
	private static String p = "8091";
	private static String role = "ADMIN";
	private static String classLoader = null;

	static GlobalSettings settings;
	static JSONArray initialSettings = null;

	/**
	 * Main Runtime application starting point
	 *
	 * @param args
	 * @throws IOException
	 */
	public static void main(String[] args) throws Exception {
		try {
			settings = new GlobalSettings();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}

		System.out.println("============================================================");
		System.out.println("Version: 0.9.6.0 Build: 2025-05.15");
		System.out.println("============================================================");

		Calendar c = Calendar.getInstance();
		TimeZone timezone = c.getTimeZone();
		TimeZone.setDefault(null);
		System.out.println("Local TimeZone is : " + timezone.getDisplayName());

		String key = null;

		String env_port = EnvironmentalVariables.getEnvKey("SERVER_PORT");
		if (env_port != null) p = env_port;

		if (settings.getCorpora() != null)
			initialSettings = settings.getCorpora();

		context = SpringApplication.run(RuntimeApplication.class, args);

		LOGGER.info("RuntimeApplication: ecosystem.Ai Client Pulse Responder Started on port number: " + p);
		System.out.println("====================================================================================");
		System.out.println("Client Pulse Responder started. For more info go to : https://ecosystem.ai");
		System.out.println("====================================================================================");

	}

	/*****************************************************************************************************************
	 * Swagger API documentation
	 *****************************************************************************************************************/
	/** https://springdoc.org/faq.html */
	@Bean
	public GroupedOpenApi publicApi() {
		return GroupedOpenApi.builder()
				.group("ecosystem-public")
				.pathsToMatch("/**")
				.build();
	}

	@Bean
	public OpenAPI ecosystemAiApi() {
		return new OpenAPI()
				.info(
						new Info().title("ecosystem.Ai Client Pulse Responder API")
								.description("The ecosystem.Ai Client Pulse Responder Engine brings the power of real-time and near-time predictions to the enterprise. Implement your behavioral construct " +
										"and core hypotheses through a configurable prediction platform. If you don't know how the model is going to behave, use our behavioral tracker" +
										"to assist with selection and exploit the most successful options.")
								.version("v0.9.6")
								.license(new License().name("ecosystem.Ai 1.0").url("https://ecosystem.ai")))
				.externalDocs(new ExternalDocumentation()
						.description("Learn Ecosystem")
						.url("https://developer.ecosystem.ai")
				).components(new Components()

						//API Key, see: https://swagger.io/docs/specification/authentication/api-keys/
						.addSecuritySchemes("apiKeyScheme", new SecurityScheme()
								.type(SecurityScheme.Type.APIKEY)
								.in(SecurityScheme.In.HEADER)
								.name("X-API-KEY")
						)
				).addSecurityItem(new SecurityRequirement().addList("apiKeyScheme"));
	}


	/* This is to turn security off. Username and password is in the application.properties file */
	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		if (!securityFlag) {
			// System.out.println("Loading...");
			http.csrf(csrf -> csrf.disable())
					.authorizeHttpRequests(auth -> auth
							.requestMatchers(HttpMethod.GET, "/**").permitAll()
							.requestMatchers(HttpMethod.POST, "/**").permitAll()
							.requestMatchers(HttpMethod.PUT, "/**").permitAll()
							.requestMatchers("/actuator/**").permitAll());
		} else if (securityFlag) {
			if (ip != null) {
				System.out.println("Secure on: " + ip);
				if (role.contains("USER")) {
					http.csrf(csrf -> csrf.disable())
							.authorizeHttpRequests(auth -> auth.anyRequest())
							.httpBasic(Customizer.withDefaults());
				} else if (role.contains("ADMIN")) {
					http.csrf(csrf -> csrf.disable())
							.authorizeHttpRequests(auth -> auth.anyRequest())
							.httpBasic(Customizer.withDefaults());
				}
			} else {
				System.out.println("Secure :)");
				http.csrf(csrf -> csrf.disable())
						.authorizeHttpRequests(auth -> auth
								.requestMatchers("/**").authenticated()
								.requestMatchers("/*").authenticated())
						.httpBasic(Customizer.withDefaults());
			}
		}
		return http.build();
	}

	/*****************************************************************************************************************
	 * Scheduling engine for model creating and scoring updates.
	 *****************************************************************************************************************/
	@EnableScheduling
	@EnableAsync
	class ScheduledActivity {
		private long count = 0;

		MongoClient mongoClient = new ConnectionFactory().getMongoClient();

		RollingEcosystemRewards rollingEcosystemRewards = null;
		RollingNaiveBayes rollingNaiveBayes = new RollingNaiveBayes(mongoClient);
		RollingBehavior rollingBehavior = new RollingBehavior(mongoClient);
		RollingNetwork rollingNetwork = new RollingNetwork(mongoClient);
		RollingQLearning rollingQLearning = new RollingQLearning(mongoClient);

		/**
		 * PROCESS DYNAMIC CONFIGURATION: Continuous scheduling engine.
		 * Set MONITORING_DELAY in seconds for processing, default is set to 10 mins.
		 */
		@Async
		@Qualifier(value = "taskExecutor")
		// @Scheduled(cron = "*/20 * * * * *") // 240000 = 4 mins, 420000 = 7 mins
		// @Scheduled(fixedDelayString = "${monitoring.delay}000", initialDelay = 10000)
		@Scheduled(fixedDelayString = "${monitoring.delay}000")
		public void scheduleFixedRateTaskAsync() throws Exception {

			settings = new GlobalSettings();
			if (settings.getCorpora() != null && mongoClient != null) {

				if (initialSettings == null)
					initialSettings = new JSONArray();

				/** Changes in settings */
				if (!initialSettings.toString().equals(settings.getCorpora().toString())) {
					System.out.println("Settings changed, restarting...");
					if (mongoClient != null)
						mongoClient.close();
					mongoClient = new ConnectionFactory().getMongoClient();
					rollingEcosystemRewards = new RollingEcosystemRewards(mongoClient);
					rollingEcosystemRewards.dynamicRecommender(mongoClient, settings);
					initialSettings = settings.getCorpora();
					return;
				} else if (rollingEcosystemRewards == null) {
					rollingEcosystemRewards = new RollingEcosystemRewards(mongoClient);
					rollingEcosystemRewards.dynamicRecommender(mongoClient, settings);
				}

				System.out.println("Scheduler: " + count + " - " + RollingEcosystemRewards.nowDate());

				if (rollingEcosystemRewards != null) {

					JSONObject paramDoc = rollingEcosystemRewards.checkCorpora(settings);
					if (!paramDoc.isEmpty()) {

						try {
							String algo = paramDoc.getJSONObject("randomisation").getString("approach");

							System.out.println("A=====================================================================================================================");
							System.out.println("A===>>> Execute Dynamic Engine for: " + paramDoc.get("name") + " [" + algo + "] on (" + count + "): " + RollingEcosystemRewards.nowDate());
							System.out.println("A=====================================================================================================================");

							/** PROCESS INDEXES ONCE PER STARTUP */
							if (count == 0)
								rollingEcosystemRewards.indexes(mongoClient);

							if (algo.equals("binaryThompson"))
								rollingEcosystemRewards.process(paramDoc);
							if (algo.equals("naiveBayes"))
								rollingNaiveBayes.process(paramDoc);
							if (algo.equals("behaviorAlgos"))
								rollingBehavior.process(paramDoc);
							if (algo.equals("Network"))
								rollingNetwork.process(paramDoc);
							if (algo.equals("QLearning"))
								rollingQLearning.process(paramDoc);

						} catch (Exception e) {

							System.out.println("B=====================================================================================================================");
							System.out.println("B===>>> Dynamic engine not processing. Updating all settings now...");
							System.out.println("B=====================================================================================================================");

							System.out.println("Settings changed, restarting...");
							if (mongoClient != null)
								mongoClient.close();
							mongoClient = new ConnectionFactory().getMongoClient();
							rollingEcosystemRewards = new RollingEcosystemRewards(mongoClient);
							rollingEcosystemRewards.dynamicRecommender(mongoClient, settings);
							initialSettings = settings.getCorpora();

						}
					}

					count = count + 1;
				}
			}
		}
	}


	public static ConfigurableApplicationContext getContext() {
		return context;
	}
}
