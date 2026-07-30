package com.jazzlogs.backend.config;

import org.h2.server.web.JakartaWebServlet;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

// Spring Boot's usual spring.h2.console.enabled autoconfiguration isn't on
// this project's classpath (this Spring Boot version's granular starters
// don't pull it in) — registered by hand instead, same servlet it wraps
// underneath. JakartaWebServlet (not WebServlet, which extends the old
// javax.servlet.http.HttpServlet — incompatible here) since this project is
// on the Jakarta Servlet API. dev-profile only; see SecurityConfig for the
// matching permitAll.
@Configuration
@Profile("dev")
public class H2ConsoleConfig {

    @Bean
    ServletRegistrationBean<JakartaWebServlet> h2Console() {
        return new ServletRegistrationBean<>(new JakartaWebServlet(), "/h2-console/*");
    }
}
