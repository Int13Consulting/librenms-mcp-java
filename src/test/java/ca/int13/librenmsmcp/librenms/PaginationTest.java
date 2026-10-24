package ca.int13.librenmsmcp.librenms;

import static org.assertj.core.api.Assertions.assertThat;

import ca.int13.librenmsmcp.json.Json;
import org.junit.jupiter.api.Test;

/** Cases follow upstream {@code paginate_list}; output text includes key order. */
class PaginationTest {
    private static String page(String json, int limit, int offset, String key) {
        return Json.write(Pagination.paginate(Json.parse(json), limit, offset, key));
    }

    @Test
    void slicesTopLevelList() {
        assertThat(page("[1,2,3,4]", 2, 1, null))
                .isEqualTo("{\"items\":[2,3],\"count\":2,\"total\":4,\"limit\":2,\"offset\":1}");
    }

    @Test
    void movesListToEndAndKeepsExistingCountPosition() {
        assertThat(page("{\"status\":\"ok\",\"count\":3,\"devices\":[1,2,3],\"x\":1}", 1, 0, "devices"))
                .isEqualTo(
                        "{\"status\":\"ok\",\"count\":1,\"x\":1,\"devices\":[1],\"total\":3,\"limit\":1,\"offset\":0}");
    }

    @Test
    void autoDetectsLongestListWithStableTies() {
        assertThat(page("{\"a\":[1],\"b\":[1,2],\"c\":[3,4]}", 10, 0, null))
                .isEqualTo("{\"a\":[1],\"c\":[3,4],\"b\":[1,2],\"count\":2,\"total\":2,\"limit\":10,\"offset\":0}");
    }

    @Test
    void offsetBeyondEndGivesEmptyPage() {
        assertThat(page("{\"devices\":[1,2]}", 5, 9, "devices"))
                .isEqualTo("{\"devices\":[],\"count\":0,\"total\":2,\"limit\":5,\"offset\":9}");
    }

    @Test
    void errorsAndNonListsPassThrough() {
        String error = "{\"status\":\"error\",\"message\":\"x\",\"devices\":[1]}";
        assertThat(page(error, 1, 0, "devices")).isEqualTo(error);
        assertThat(page("{\"status\":\"ok\",\"devices\":{}}", 1, 0, "devices"))
                .isEqualTo("{\"status\":\"ok\",\"devices\":{}}");
        assertThat(page("{\"status\":\"ok\"}", 1, 0, null)).isEqualTo("{\"status\":\"ok\"}");
        assertThat(page("\"text\"", 1, 0, null)).isEqualTo("\"text\"");
    }
}
