package bio.cosy.featurecloud.orchestration.docker.registry;

import bio.cosy.featurecloud.orchestration.api.orchestration.RegistryRepositoryDTO;
import io.quarkus.rest.client.reactive.Url;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Function;

@RegisterRestClient(configKey = "docker-registry")
@ClientHeaderParam(name = "Authorization", value = "{registryAuthorization}")
@Produces(MediaType.APPLICATION_JSON)
public interface DockerRegistryRestClient {
    @GET
    @Path("/v2/_catalog")
    Response listRepositories(@QueryParam("n") int pageSize);

    @GET
    @Path("/v2/{repository:.+}/tags/list")
    Response listTags(@Encoded @PathParam("repository") String repository, @QueryParam("n") int pageSize);

    @GET
    Response getPage(@Url URI uri);

    default List<String> listAllRepositories() {
        return listAll(listRepositories(100), endpoint("_catalog"), RegistryCatalogDTO.class, RegistryCatalogDTO::getRepositories);
    }

    default List<String> listAllTags(String repository) {
        return listAll(listTags(repository, 100), endpoint(repository + "/tags/list"), RegistryRepositoryDTO.class, RegistryRepositoryDTO::getTags);
    }

    private URI endpoint(String path) {
        URI base = ConfigProvider.getConfig().getValue("quarkus.rest-client.docker-registry.url", URI.class);
        return UriBuilder.fromUri(base).path("v2").path(path).build();
    }

    private <T> List<String> listAll(Response response, URI page, Class<T> type, Function<T, List<String>> entries) {
        var values = new TreeSet<String>();
        var visited = new HashSet<URI>();
        visited.add(page);
        while (response != null) {
            URI next = null;
            try (var current = response) {
                if (current.getStatus() != 200) {
                    throw new WebApplicationException("Registry listing failed (HTTP " + current.getStatus()
                            + "). Check registry access and support for the Registry V2 catalogue API.", 502);
                }
                T body = current.readEntity(type);
                if (body == null) {
                    throw new WebApplicationException("Registry returned an empty listing response", 502);
                }
                var items = entries.apply(body);
                if (items != null) {
                    values.addAll(items);
                }
                var link = current.getLink("next");
                if (link != null) {
                    next = page.resolve(link.getUri());
                    if (!page.getScheme().equals(next.getScheme()) || !page.getAuthority().equals(next.getAuthority())
                            || !page.getPath().equals(next.getPath()) || !visited.add(next)) {
                        throw new WebApplicationException("Registry returned an invalid pagination link", 502);
                    }
                }
            }
            page = next;
            response = page == null ? null : getPage(page);
        }
        return List.copyOf(values);
    }
}
