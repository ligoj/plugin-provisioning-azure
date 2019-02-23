/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.azure;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import javax.transaction.Transactional;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpStatus;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ligoj.app.AbstractServerTest;
import org.ligoj.app.iam.model.CacheCompany;
import org.ligoj.app.iam.model.CacheUser;
import org.ligoj.app.model.DelegateNode;
import org.ligoj.app.model.Node;
import org.ligoj.app.model.Parameter;
import org.ligoj.app.model.ParameterValue;
import org.ligoj.app.model.Project;
import org.ligoj.app.model.Subscription;
import org.ligoj.app.plugin.prov.azure.catalog.ProvAzurePriceImportResource;
import org.ligoj.app.plugin.prov.model.ProvLocation;
import org.ligoj.app.plugin.prov.model.ProvQuote;
import org.ligoj.app.resource.subscription.SubscriptionResource;
import org.ligoj.bootstrap.MatcherUtil;
import org.ligoj.bootstrap.core.curl.CurlRequest;
import org.ligoj.bootstrap.core.resource.BusinessException;
import org.ligoj.bootstrap.core.validation.ValidationJsonException;
import org.ligoj.bootstrap.resource.system.configuration.ConfigurationResource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.support.ExecutorServiceAdapter;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import com.github.tomakehurst.wiremock.matching.EqualToPattern;
import com.microsoft.aad.adal4j.AuthenticationContext;
import com.microsoft.aad.adal4j.AuthenticationResult;
import com.microsoft.aad.adal4j.ClientCredential;

/**
 * Test class of {@link ProvAzurePluginResource}
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = "classpath:/META-INF/spring/application-context-test.xml")
@Rollback
@Transactional
public class ProvAzurePluginResourceTest extends AbstractServerTest {

	protected int subscription;

	@Autowired
	private ProvAzurePluginResource resource;

	@Autowired
	private SubscriptionResource subscriptionResource;

	@Autowired
	private ConfigurationResource configuration;

	@BeforeEach
	public void prepareData() throws IOException {
		persistSystemEntities();
		persistEntities("csv",
				new Class[] { Node.class, Project.class, CacheCompany.class, CacheUser.class, DelegateNode.class,
						Subscription.class, ProvLocation.class, ProvQuote.class, Parameter.class,
						ParameterValue.class },
				StandardCharsets.UTF_8.name());
		configuration.put("service:prov:azure:management", "http://localhost:" + MOCK_PORT + "/");
		configuration.put("service:prov:azure:authority", "https://localhost:" + MOCK_PORT + "/");
		this.subscription = getSubscription("gStack");

		// Invalidate azure cache
		cacheManager.getCache("curl-tokens").clear();
	}

	@Test
	public void getKey() {
		Assertions.assertEquals("service:prov:azure", resource.getKey());
	}

	@Test
	public void install() throws IOException {
		final ProvAzurePluginResource resource2 = new ProvAzurePluginResource();
		resource2.priceImport = Mockito.mock(ProvAzurePriceImportResource.class);
		resource2.install();
	}

	@Test
	public void updateCatalog() throws IOException {
		// Re-Install a new configuration
		final ProvAzurePluginResource resource2 = new ProvAzurePluginResource();
		super.applicationContext.getAutowireCapableBeanFactory().autowireBean(resource2);
		resource2.priceImport = Mockito.mock(ProvAzurePriceImportResource.class);
		resource2.updateCatalog("service:prov:aws:test");
	}

	@Test
	public void updateCatalogNoRight() {
		initSpringSecurityContext("any");

		// Re-Install a new configuration
		Assertions.assertEquals("read-only-node", Assertions.assertThrows(BusinessException.class, () -> {
			resource.updateCatalog("service:prov:aws:test");
		}).getMessage());
	}

	@Test
	public void create() throws Exception {
		prepareMockAuth();
		newResource().create(subscription);
	}

	/**
	 * Return the subscription identifier of the given project. Assumes there is only one subscription for a service.
	 */
	protected int getSubscription(final String project) {
		return getSubscription(project, ProvAzurePluginResource.KEY);
	}

	private void prepareMockAuth() throws IOException {
		httpServer.stubFor(get(urlPathEqualTo("/11112222-3333-4444-5555-666677778888"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK)
						.withBody(IOUtils.toString(
								new ClassPathResource("mock-server/azure/authentication-oauth.json").getInputStream(),
								StandardCharsets.UTF_8))));
		httpServer.start();
	}

	private ProvAzurePluginResource newResource()
			throws InterruptedException, ExecutionException, MalformedURLException {
		return newResource(newExecutorService());
	}

	private ExecutorService newExecutorService() {
		final TaskExecutor taskExecutor = Mockito.mock(TaskExecutor.class);
		return new ExecutorServiceAdapter(taskExecutor) {

			@Override
			public void shutdown() {
				// Do nothing
			}
		};

	}

	@Test
	public void checkStatus() throws Exception {
		prepareMockAuth();
		Assertions.assertTrue(newResource().checkStatus(subscriptionResource.getParametersNoCheck(subscription)));
	}

	/**
	 * Authority does not respond : no defined mock
	 */
	@Test
	public void checkStatusAuthorityFailed() {
		MatcherUtil.assertThrows(Assertions.assertThrows(ValidationJsonException.class, () -> {
			resource.checkStatus(subscriptionResource.getParametersNoCheck(subscription));
		}), AbstractAzureToolPluginResource.PARAMETER_KEY, "azure-login");
	}

	/**
	 * Authority error, client side
	 */
	@Test
	public void checkStatusAuthorityError() {
		Assertions.assertThrows(IllegalStateException.class, () -> {
			newResourceFailed().checkStatus(subscriptionResource.getParametersNoCheck(subscription));
		});
	}

	/**
	 * Authority is valid, but the token cannot be acquired
	 */
	@Test
	public void checkStatusShudownFailed() throws Exception {
		prepareMockAuth();
		final TaskExecutor taskExecutor = Mockito.mock(TaskExecutor.class);
		final ProvAzurePluginResource resource = newResource(new ExecutorServiceAdapter(taskExecutor) {

			@Override
			public void shutdown() {
				throw new IllegalStateException();
			}
		});
		Assertions.assertThrows(IllegalStateException.class, () -> {
			resource.checkStatus(subscriptionResource.getParametersNoCheck(subscription));
		});
	}

	@Test
	public void processor() {
		httpServer.stubFor(get(urlPathEqualTo("/")).withHeader("Authorization", new EqualToPattern("Bearer TOKEN"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK)));
		httpServer.start();
		try (AzureCurlProcessor curl = new AzureCurlProcessor()) {
			curl.setToken("TOKEN");
			Assertions.assertTrue(curl.process(new CurlRequest("GET", "http://localhost:" + MOCK_PORT + "/")));
		}
	}

	private ProvAzurePluginResource newResource(final ExecutorService service)
			throws InterruptedException, ExecutionException, MalformedURLException {
		ProvAzurePluginResource resource = new ProvAzurePluginResource();
		applicationContext.getAutowireCapableBeanFactory().autowireBean(resource);
		resource = Mockito.spy(resource);
		final AuthenticationContext context = Mockito.mock(AuthenticationContext.class);
		@SuppressWarnings("unchecked")
		final Future<AuthenticationResult> future = Mockito.mock(Future.class);
		final AuthenticationResult result = new AuthenticationResult("-token-", "-token-", "-token-", 10000, "-token-",
				null, true);
		Mockito.doReturn(result).when(future).get();
		Mockito.doReturn(future).when(context).acquireToken(ArgumentMatchers.anyString(),
				ArgumentMatchers.any(ClientCredential.class), ArgumentMatchers.any());
		Mockito.doReturn(context).when(resource).newAuthenticationContext("11112222-3333-4444-5555-666677778888",
				service);
		Mockito.doReturn(service).when(resource).newExecutorService();
		return resource;
	}

	private ProvAzurePluginResource newResourceFailed() throws MalformedURLException {
		final ExecutorService service = newExecutorService();
		ProvAzurePluginResource resource = new ProvAzurePluginResource();
		applicationContext.getAutowireCapableBeanFactory().autowireBean(resource);
		resource = Mockito.spy(resource);
		Mockito.doThrow(IllegalStateException.class).when(resource)
				.newAuthenticationContext("11112222-3333-4444-5555-666677778888", service);
		Mockito.doReturn(service).when(resource).newExecutorService();
		return resource;
	}

	@Test
	public void getVersion() throws Exception {
		final String version = resource.getVersion(subscription);
		Assertions.assertEquals("2017-03-30", version);
	}

}
