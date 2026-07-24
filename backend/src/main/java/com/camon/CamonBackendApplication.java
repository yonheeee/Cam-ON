package com.camon;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication //웹 서버라는 어노테이션
@ConfigurationPropertiesScan 
public class CamonBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(CamonBackendApplication.class, args);
		
	} 	

}
