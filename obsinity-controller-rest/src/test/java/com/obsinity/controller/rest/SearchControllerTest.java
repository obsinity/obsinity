package com.obsinity.controller.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsinity.service.core.objql.OBJql;
import com.obsinity.service.core.objql.OBJqlPage;
import com.obsinity.service.core.repo.ServicesCatalogRepository;
import com.obsinity.service.core.search.SearchService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SearchControllerTest {

    @Test
    void omitsTotalWhenNotRequested() {
        SearchService search = mock(SearchService.class);
        ServicesCatalogRepository services = mock(ServicesCatalogRepository.class);
        when(services.findPartitionKeyByServiceKey("payments")).thenReturn("df384ae9");
        when(search.query(any(OBJql.class), any(OBJqlPage.class), eq(false)))
                .thenReturn(List.of(Map.of("event_id", "e1")));

        SearchController controller = new SearchController(search, new ObjectMapper(), services, "data", "links");
        SearchController.SearchBody body = new SearchController.SearchBody();
        body.service = "payments";
        body.event = "user_profile.updated";
        body.period = new SearchController.Period();
        body.period.previous = "-30m";
        body.limit = 100;
        body.offset = 0L;

        Map<String, Object> response = controller.search(body);

        assertThat(response).doesNotContainKey("total");
        verify(search).query(any(OBJql.class), any(OBJqlPage.class), eq(false));
        verify(search, never()).query(any(OBJql.class), any(OBJqlPage.class), eq(true));
    }

    @Test
    void includesTotalWhenRequested() {
        SearchService search = mock(SearchService.class);
        ServicesCatalogRepository services = mock(ServicesCatalogRepository.class);
        when(services.findPartitionKeyByServiceKey("payments")).thenReturn("df384ae9");
        when(search.query(any(OBJql.class), any(OBJqlPage.class), eq(true)))
                .thenReturn(List.of(Map.of("event_id", "e1", "matched_count", 42L)));

        SearchController controller = new SearchController(search, new ObjectMapper(), services, "data", "links");
        SearchController.SearchBody body = new SearchController.SearchBody();
        body.service = "payments";
        body.event = "user_profile.updated";
        body.period = new SearchController.Period();
        body.period.previous = "-30m";
        body.limit = 100;
        body.offset = 0L;
        body.includeTotal = true;

        Map<String, Object> response = controller.search(body);

        assertThat(response).containsEntry("total", 42L);
        verify(search).query(any(OBJql.class), any(OBJqlPage.class), eq(true));
    }
}
