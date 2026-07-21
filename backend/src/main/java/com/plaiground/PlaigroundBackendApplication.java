package com.plaiground;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication //웹 서버라는 어노테이션
@ConfigurationPropertiesScan 
public class PlaigroundBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(PlaigroundBackendApplication.class, args);
		
	} 	

}
