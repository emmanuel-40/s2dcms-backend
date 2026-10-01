package com.myproject.S2dcms.Service;

import com.myproject.S2dcms.Exception.FileUploadException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Covers the branch that decides whether uploads live in the container or in Supabase,
 * plus the guarantee that provider detail is never leaked to the client.
 */
class SupabaseStorageServiceTest {

    private static final String URL = "https://example-project.supabase.co";
    private static final String KEY = "service-role-secret";
    private static final String BUCKET = "s2dcms-uploads";

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private SupabaseStorageService service;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service = new SupabaseStorageService(builder);
        inject(service, "storageUrl", URL);
        inject(service, "serviceKey", KEY);
        inject(service, "bucket", BUCKET);
    }

    /** The service reads these with @Value, so they are set directly for a plain unit test. */
    private static void inject(SupabaseStorageService target, String field, String value) {
        try {
            var f = SupabaseStorageService.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static MockMultipartFile png(String name) {
        return new MockMultipartFile("image", name, MediaType.IMAGE_PNG_VALUE, new byte[]{1, 2, 3});
    }

    @Test
    @DisplayName("isConfigured() is false unless both the URL and the service key are present")
    void isConfiguredRequiresBothUrlAndKey() {
        assertThat(service.isConfigured()).isTrue();

        inject(service, "serviceKey", "");
        assertThat(service.isConfigured()).isFalse();

        inject(service, "serviceKey", KEY);
        inject(service, "storageUrl", "");
        assertThat(service.isConfigured()).isFalse();
    }

    @Test
    @DisplayName("upload() POSTs the bytes with the service key and returns the object key")
    void uploadStoresTheFileAndReturnsObjectKey() {
        server.expect(requestTo(URL + "/storage/v1/object/" + BUCKET + "/profile/pic.png"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + KEY))
                .andExpect(content().bytes(new byte[]{1, 2, 3}))
                .andRespond(withSuccess());

        String objectKey = service.upload(png("pic.png"), "profile", "pic.png");

        // The stored value stays "<folder>/<name>" so FileStorageService can prefix it with
        // "/uploads/" exactly as it always has - no database migration, no frontend change.
        assertThat(objectKey).isEqualTo("profile/pic.png");
        assertThat(SupabaseStorageService.UPLOAD_PATH_PREFIX + objectKey)
                .isEqualTo("/uploads/profile/pic.png");
        server.verify();
    }

    @Test
    @DisplayName("a filename with a space is encoded once, not twice, when it reaches the bucket")
    void uploadEncodesTheFilenameExactlyOnce() {
        server.expect(requestTo(URL + "/storage/v1/object/" + BUCKET + "/profile/my%20photo.png"))
                .andRespond(withSuccess());

        String key = service.upload(png("my photo.png"), "profile", "my photo.png");

        // RestClient does the encoding, so the STORED key keeps the plain space: it is stored
        // behind a "/uploads/..." URL, not embedded in a provider URL that would need escaping.
        assertThat(key).isEqualTo("profile/my photo.png");
        server.verify();
    }

    @Test
    @DisplayName("a provider failure raises a fixed message instead of leaking provider detail")
    void uploadFailureDoesNotLeakProviderDetail() {
        server.expect(requestTo(URL + "/storage/v1/object/" + BUCKET + "/profile/pic.png"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> service.upload(png("pic.png"), "profile", "pic.png"))
                .isInstanceOf(FileUploadException.class)
                .hasMessage("Failed to store the file. Please try again.")
                .hasMessageNotContaining("example-project")
                .hasMessageNotContaining(KEY);
    }

    @Test
    @DisplayName("delete() removes the object and swallows failures so callers are unaffected")
    void deleteRemovesTheObjectAndNeverThrows() {
        server.expect(requestTo(URL + "/storage/v1/object/" + BUCKET + "/profile/pic.png"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("Authorization", "Bearer " + KEY))
                .andRespond(withSuccess());

        assertThatCode(() -> service.delete("profile", "pic.png")).doesNotThrowAnyException();
        server.verify();

        // A failing delete must not propagate: removing a profile picture must still succeed,
        // which is the same contract the previous local-disk implementation had.
        MockRestServiceServer failing = MockRestServiceServer.bindTo(RestClient.builder()).build();
        SupabaseStorageService unreachable = new SupabaseStorageService(RestClient.builder());
        inject(unreachable, "storageUrl", "http://localhost:1");
        inject(unreachable, "serviceKey", KEY);
        inject(unreachable, "bucket", BUCKET);

        assertThatCode(() -> unreachable.delete("profile", "pic.png")).doesNotThrowAnyException();
        assertThat(failing).isNotNull();
    }

    @Test
    @DisplayName("download() returns the bytes, and empty when the object is a 404")
    void downloadReturnsBytesAndTreatsMissingObjectsAsEmpty() {
        server.expect(requestTo(URL + "/storage/v1/object/" + BUCKET + "/profile/pic.png"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(new byte[]{1, 2, 3}, MediaType.APPLICATION_OCTET_STREAM));

        assertThat(service.download("profile", "pic.png")).containsExactly(1, 2, 3);
        server.verify();

        server.reset();
        server.expect(requestTo(URL + "/storage/v1/object/" + BUCKET + "/profile/gone.png"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        // A missing file must be answerable as 404, not surfaced as a provider failure.
        assertThat(service.download("profile", "gone.png")).isEmpty();
    }

    @Test
    @DisplayName("a traversal-style filename is neutralised before it reaches the bucket")
    void filenamesCannotEscapeTheirFolder() {
        server.expect(requestTo(URL + "/storage/v1/object/" + BUCKET + "/profile/.._etc_passwd"))
                .andRespond(withSuccess());

        String key = service.upload(png("../etc_passwd"), "profile", "../etc_passwd");

        assertThat(key).isEqualTo("profile/.._etc_passwd");
        server.verify();
    }
}
