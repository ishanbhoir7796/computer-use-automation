package com.interfaceai.cuacore;

import com.interfaceai.cuacore.agent.AnthropicProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AnthropicProperties.class)
public class CuaCoreApplication {
	public static void main(String[] args) {
		SpringApplication.run(CuaCoreApplication.class, args);
	}
}