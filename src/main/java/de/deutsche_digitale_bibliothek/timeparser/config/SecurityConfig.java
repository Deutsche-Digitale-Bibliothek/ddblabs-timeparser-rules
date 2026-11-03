package de.deutsche_digitale_bibliothek.timeparser.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Konfiguriert die gemeinsame Formularanmeldung des Regel-Editors.
 *
 * <p>Benutzername und Passwort kommen aus der externen Konfiguration, damit der
 * gleiche Container in lokalen und produktionsnahen Umgebungen nutzbar bleibt.
 * Es gibt genau einen konfigurierten In-Memory-Benutzer; die Anwendung besitzt
 * keine eigene Benutzer- oder Sitzungsdatenbank.</p>
 *
 * <p>WebJars sowie die Anmeldeseite sind öffentlich erreichbar. Alle anderen
 * Requests verlangen eine Anmeldung. Die vergebene Rolle {@code ADMIN} wird
 * hier nicht für feinere Rechteunterscheidungen ausgewertet.</p>
 */
@Configuration
public class SecurityConfig {

    /**
     * Verknüpft die Zugriffsvorgaben mit der eigenen Login-Seite.
     * Nach erfolgreicher Anmeldung wird stets die Gruppenliste geöffnet.
     *
     * @param http von Spring Security bereitgestellter Konfigurationsaufbau
     * @return Filterkette für die HTTP-Requests der Anwendung
     * @throws Exception wenn die Sicherheitskonfiguration nicht aufgebaut werden kann
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/webjars/**").permitAll()
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .defaultSuccessUrl("/rules", true)
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutSuccessUrl("/login?logout")
                        .permitAll()
                )
                .build();
    }

    /**
     * Erzeugt den konfigurierten Benutzer und codiert sein Klartextpasswort beim Start.
     * Das codierte Passwort wird nur im Arbeitsspeicher gehalten.
     *
     * @param username konfigurierter Anmeldename
     * @param password konfiguriertes Klartextpasswort
     * @param passwordEncoder Encoder für den späteren Passwortvergleich
     * @return Benutzerverwaltung mit genau einem Benutzer der Rolle {@code ADMIN}
     */
    @Bean
    public UserDetailsService userDetailsService(@Value("${timeparser.security.username}") String username,
                                                 @Value("${timeparser.security.password}") String password,
                                                 PasswordEncoder passwordEncoder) {
        return new InMemoryUserDetailsManager(User.withUsername(username)
                .password(passwordEncoder.encode(password))
                .roles("ADMIN")
                .build());
    }

    /**
     * Legt BCrypt als gemeinsamen Encoder für Speicherung im Speicher und Vergleich fest.
     *
     * @return neuer BCrypt-Passwortencoder
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
