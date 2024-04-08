package com.ecosystem.runtime;

import com.ecosystem.data.mongodb.ConnectionFactory;
import com.ecosystem.runtime.continuous.*;
import com.ecosystem.utils.GlobalSettings;
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
import org.json.JSONArray;
import org.json.JSONObject;
import org.springdoc.core.GroupedOpenApi;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.cassandra.CassandraDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.cassandra.CassandraReactiveDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.mongo.MongoRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoReactiveAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;

import javax.annotation.PostConstruct;

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
@Configuration
@EnableWebSecurity
@EnableScheduling
public class RuntimeApplication extends WebSecurityConfigurerAdapter {

	static GlobalSettings settings;
	static JSONArray initialSettings = null;

	public static void main(String[] args) {
		System.out.println("============================================================");
		System.out.println("Version: 0.9.4.2 Build: 2024-04.11");
		System.out.println("============================================================");

		try {
			settings = new GlobalSettings();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		if (settings.getCorpora() != null)
			initialSettings = settings.getCorpora();

		SpringApplication.run(RuntimeApplication.class, args);

		System.out.println("====================================================================================");
		System.out.println("Client Pulse Responder started. For more info go to : https://ecosystem.ai");
		System.out.println("====================================================================================");
	}

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
								.version("v0.9.4")
								.license(new License().name("ecosystem.Ai 1.0").url("https://ecosystem.ai")))
				.externalDocs(new ExternalDocumentation()
						.description("Learn Ecosystem")
						.url("https://learn.ecosystem.ai")
				).components(new Components()
						.addSecuritySchemes("apiKeyScheme", new SecurityScheme()
								.type(SecurityScheme.Type.APIKEY)
								.in(SecurityScheme.In.HEADER)
								.name("X-API-KEY")
						)
				).addSecurityItem(new SecurityRequirement().addList("apiKeyScheme"));
	}

	/* This is to turn security off. Username and password is in the application.properties file */
	@Override
	protected void configure(HttpSecurity http) throws Exception {
		System.out.println("Loading...");
		http.csrf().disable()
				.authorizeRequests()
				.antMatchers(HttpMethod.GET, "/**").permitAll()
				.antMatchers(HttpMethod.POST, "/**").permitAll()
				.antMatchers(HttpMethod.PUT, "/**").permitAll();
		System.out.println("Loaded...");
	}

	/*****************************************************************************************************************
	 * Scheduling engine for model creating and scoring updates.
	 *****************************************************************************************************************/
	@EnableScheduling
	@EnableAsync
	class ScheduledActivity {
		private long count = 0;

		MongoClient mongoClient = new ConnectionFactory().getMongoClient();

		RollingMaster rollingMaster = null;
		RollingNaiveBayes rollingNaiveBayes;
		RollingBehavior rollingBehavior;
		RollingNetwork rollingNetwork;

		@PostConstruct
		private void init() {
			rollingNaiveBayes = new RollingNaiveBayes(mongoClient);
			rollingBehavior = new RollingBehavior(mongoClient);
			rollingNetwork = new RollingNetwork(mongoClient);
		}
		/**
		 * PROCESS DYNAMIC CONFIGURATION: Continuous scheduling engine.
		 * Set MONITORING_DELAY in seconds for processing, default is set to 10 mins.
		 */
		@Async
		@Qualifier(value = "taskExecutor")
		@Scheduled(fixedDelayString = "${monitoring.delay}000")
		public void scheduleFixedRateTaskAsync() throws Exception {

			settings = new GlobalSettings();
			if (settings.getCorpora() != null) {

				if (initialSettings == null)
					initialSettings = new JSONArray();

				/** Changes in settings */
				if (!initialSettings.toString().equals(settings.getCorpora().toString())) {
					System.out.println("Settings changed, restarting...");
					mongoClient.close();
					mongoClient = new ConnectionFactory().getMongoClient();
					rollingMaster = new RollingMaster(mongoClient);
					rollingMaster.dynamicRecommender(mongoClient, settings);
					initialSettings = settings.getCorpora();
					return;
				} else if (rollingMaster == null) {
					rollingMaster = new RollingMaster(mongoClient);
					rollingMaster.dynamicRecommender(mongoClient, settings);
				}

				System.out.println("Scheduler: " + count + " - " + RollingMaster.nowDate());

				if (rollingMaster != null) {

					JSONObject paramDoc = rollingMaster.checkCorpora(settings);
					if (!paramDoc.isEmpty()) {

						String algo = paramDoc.getJSONObject("randomisation").getString("approach");

						System.out.println("A=====================================================================================================================");
						System.out.println("A===>>> Execute Dynamic Engine for: " + paramDoc.get("name") + " [" + algo + "] on (" + count + "): " + RollingMaster.nowDate());
						System.out.println("A=====================================================================================================================");

						/** PROCESS INDEXES ONCE PER STARTUP */
						if (count == 0)
							rollingMaster.indexes(mongoClient);

						if (algo.equals("binaryThompson"))
							rollingMaster.process(paramDoc);
						if (algo.equals("naiveBayes"))
							rollingNaiveBayes.process(paramDoc);
						if (algo.equals("behaviorAlgos"))
							rollingBehavior.process(paramDoc);
						if (algo.equals("Network"))
							rollingNetwork.process(paramDoc);

					}

					count = count + 1;
				}
			}
		}
	}
}