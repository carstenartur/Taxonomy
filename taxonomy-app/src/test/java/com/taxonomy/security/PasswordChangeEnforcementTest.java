package com.taxonomy.security;

import com.taxonomy.security.config.PasswordChangeRequiredFilter;
import com.taxonomy.security.model.AppRole;
import com.taxonomy.security.model.AppUser;
import com.taxonomy.security.repository.RoleRepository;
import com.taxonomy.security.repository.UserRepository;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "taxonomy.security.require-password-change=true")
@AutoConfigureMockMvc
class PasswordChangeEnforcementTest {

    private static final String USERNAME = "password-restricted-user";
    private static final String TEMPORARY_PASSWORD = "TemporaryPassword1";
    private static final String REPLACEMENT_PASSWORD = "ReplacementPassword1";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private PasswordChangeRequiredFilter passwordChangeRequiredFilter;
    @Autowired
    @Qualifier("disableContainerPasswordChangeRequiredFilter")
    private FilterRegistrationBean<PasswordChangeRequiredFilter> registration;
    @Autowired
    @Qualifier("springSecurityFilterChain")
    private FilterChainProxy securityFilterChain;

    @BeforeEach
    void setUp() {
        AppRole userRole = roleRepository.findByName("ROLE_USER")
                .orElseGet(() -> roleRepository.save(new AppRole("ROLE_USER")));
        AppUser user = userRepository.findByUsername(USERNAME)
                .orElseGet(AppUser::new);
        user.setUsername(USERNAME);
        user.setPasswordHash(passwordEncoder.encode(TEMPORARY_PASSWORD));
        user.setEnabled(true);
        user.setMustChangePassword(true);
        user.setRoles(Set.of(userRole));
        userRepository.save(user);
    }

    @Test
    @WithMockUser(username = USERNAME, roles = "USER")
    void browserUserIsRedirectedUntilPasswordChanges() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/change-password"));

        mockMvc.perform(get("/change-password"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = USERNAME, roles = "USER")
    void prefixedBrowserAndRequiredAssetsRemainReachable() throws Exception {
        mockMvc.perform(prefixedGet("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/taxonomy/change-password"));

        mockMvc.perform(prefixedGet("/change-password"))
                .andExpect(status().isOk());
        mockMvc.perform(prefixedGet("/css/taxonomy.css"))
                .andExpect(status().isOk());
        mockMvc.perform(prefixedGet("/js/taxonomy-i18n.js"))
                .andExpect(status().isOk());
        mockMvc.perform(prefixedGet(
                        "/webjars/bootstrap/5.3.8/dist/css/bootstrap.min.css"))
                .andExpect(status().isOk());
        mockMvc.perform(prefixedGet("/images/not-present.svg"))
                .andExpect(status().isNotFound());
    }

    @Test
    void basicClientReceivesPreconditionAndCanReplacePasswordThroughApi()
            throws Exception {
        mockMvc.perform(get("/api/taxonomy")
                        .with(httpBasic(USERNAME, TEMPORARY_PASSWORD)))
                .andExpect(status().is(428))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.error")
                        .value("PASSWORD_CHANGE_REQUIRED"))
                .andExpect(jsonPath("$.changePasswordEndpoint")
                        .value("/api/account/change-password"));

        replacePassword(post("/api/account/change-password"));

        assertReplacementPersisted();
        mockMvc.perform(get("/api/taxonomy")
                        .with(httpBasic(USERNAME, REPLACEMENT_PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    void prefixedBasicClientUsesContextAwareReplacementEndpoint()
            throws Exception {
        mockMvc.perform(prefixedGet("/api/taxonomy")
                        .with(httpBasic(USERNAME, TEMPORARY_PASSWORD)))
                .andExpect(status().is(428))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.error")
                        .value("PASSWORD_CHANGE_REQUIRED"))
                .andExpect(jsonPath("$.changePasswordEndpoint")
                        .value("/taxonomy/api/account/change-password"));

        replacePassword(prefixedPost("/api/account/change-password"));

        assertReplacementPersisted();
        mockMvc.perform(prefixedGet("/api/taxonomy")
                        .with(httpBasic(USERNAME, REPLACEMENT_PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    void filterIsRegisteredOnlyOnceInsideTheSecurityChain() {
        List<Filter> filters = securityFilterChain.getFilters("/api/taxonomy");

        int basicIndex = indexOf(filters, BasicAuthenticationFilter.class);
        int enforcementIndex = filters.indexOf(passwordChangeRequiredFilter);

        assertThat(registration.isEnabled()).isFalse();
        assertThat(basicIndex).isGreaterThanOrEqualTo(0);
        assertThat(enforcementIndex).isGreaterThan(basicIndex);
        assertThat(filters.stream().filter(passwordChangeRequiredFilter::equals))
                .hasSize(1);
    }

    private void replacePassword(MockHttpServletRequestBuilder request)
            throws Exception {
        mockMvc.perform(request
                        .with(httpBasic(USERNAME, TEMPORARY_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currentPassword": "TemporaryPassword1",
                                  "newPassword": "ReplacementPassword1",
                                  "confirmPassword": "ReplacementPassword1"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PASSWORD_CHANGED"));
    }

    private void assertReplacementPersisted() {
        AppUser updated = userRepository.findByUsername(USERNAME).orElseThrow();
        assertThat(updated.isMustChangePassword()).isFalse();
        assertThat(passwordEncoder.matches(
                REPLACEMENT_PASSWORD, updated.getPasswordHash())).isTrue();
    }

    private static MockHttpServletRequestBuilder prefixedGet(String path) {
        return get("/taxonomy" + path)
                .contextPath("/taxonomy")
                .servletPath("/".equals(path) ? "" : path);
    }

    private static MockHttpServletRequestBuilder prefixedPost(String path) {
        return post("/taxonomy" + path)
                .contextPath("/taxonomy")
                .servletPath(path);
    }

    private static int indexOf(
            List<Filter> filters,
            Class<? extends Filter> type) {
        for (int index = 0; index < filters.size(); index++) {
            if (type.isInstance(filters.get(index))) {
                return index;
            }
        }
        return -1;
    }
}
