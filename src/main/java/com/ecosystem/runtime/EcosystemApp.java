/**
 * PLEASE DO NOT ALTER WITHOUT CHECKING WITH AN ECOSYSTEM.AI SPECIALIST
 */
package com.ecosystem.runtime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.mongo.MongoRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import springfox.documentation.builders.PathSelectors;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.service.ApiInfo;
import springfox.documentation.service.Contact;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spring.web.plugins.Docket;
import springfox.documentation.swagger2.annotations.EnableSwagger2;

import java.time.LocalDate;
import java.util.Collections;

@SpringBootApplication
@EnableSwagger2
@Configuration
@EnableWebSecurity
@EnableAutoConfiguration(exclude = {
		MongoDataAutoConfiguration.class,
		MongoRepositoriesAutoConfiguration.class,
		MongoAutoConfiguration.class
})

public class EcosystemApp extends WebSecurityConfigurerAdapter {
	public static String version;

    public static void main(String[] args) {
        SpringApplication.run(EcosystemApp.class, args);
    }

	@Bean
	public Docket ecoApi() {
		return new Docket(DocumentationType.SWAGGER_2)
				.apiInfo(apiInfo())
				.select()
				.apis(RequestHandlerSelectors.any())
				.paths(PathSelectors.any())
				.build()
				.pathMapping("/")
				.directModelSubstitute(LocalDate.class, String.class)
				.genericModelSubstitutes(ResponseEntity.class)
				.useDefaultResponseMessages(false);
	}

	private ApiInfo apiInfo() {
		return new ApiInfo(
				"ecosystem.Ai Client Pulse Responder Localbuild API",
				"The ecosystem.Ai Client Pulse Responder brings the power of real-time and near-time predictions to the enterprise. Implement your behavioral construct " +
						"and core hypotheses through a configurable prediction platform. If you don't know how the model is going to behave, use our behavioral tracker" +
						"to assist with selection and exploit the most successful options.",
				version,
				"Terms of Service",
				new Contact("ecosystem.Ai", "ecosystem.ai", "support@ecosystem.ai") {
				},
				"Licence of API", "API Licence", Collections.emptyList()
		);
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
    }

}