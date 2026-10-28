package com.jazzlogs.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling: needed for SyncRetryWorker's @Scheduled retry job.
@SpringBootApplication
@EnableScheduling
public class JazzlogsBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(JazzlogsBackendApplication.class, args);
	}

}
