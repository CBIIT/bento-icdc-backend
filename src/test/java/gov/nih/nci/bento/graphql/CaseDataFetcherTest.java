package gov.nih.nci.bento.graphql;

import graphql.schema.DataFetchingEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CaseDataFetcherTest {

    @Mock
    private Driver driver;

    @Mock
    private Session session;

    @Mock
    private Result result;

    @Mock
    private DataFetchingEnvironment env;

    @Mock
    private Record record;

    @Mock
    private Value value;

    @Test
    void get_returnsSingleItemList_whenCaseExists_andUsesSafeCypherAlias() {
        when(env.getArgument("case_id")).thenReturn("CASE-123");
        when(driver.session()).thenReturn(session);
        when(session.run(anyString(), anyMap())).thenReturn(result);
        when(result.hasNext()).thenReturn(true);
        when(result.next()).thenReturn(record);
        when(record.get("c")).thenReturn(value);

        Map<String, Object> caseNode = Map.of("case_id", "CASE-123");
        when(value.asObject()).thenReturn(caseNode);

        CaseDataFetcher fetcher = new CaseDataFetcher(driver);
        Object fetched = fetcher.get(env);

        assertInstanceOf(List.class, fetched);
        List<?> asList = (List<?>) fetched;
        assertEquals(1, asList.size());
        assertEquals(caseNode, asList.get(0));

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> paramCaptor = ArgumentCaptor.forClass(Map.class);
        verify(session).run(queryCaptor.capture(), paramCaptor.capture());
        assertTrue(queryCaptor.getValue().contains("MATCH (c:case {case_id: $case_id})"));
        assertFalse(queryCaptor.getValue().contains("MATCH (case:case"));
        assertEquals("CASE-123", paramCaptor.getValue().get("case_id"));
    }

    @Test
    void get_returnsEmptyList_whenCaseDoesNotExist() {
        when(env.getArgument("case_id")).thenReturn("MISSING");
        when(driver.session()).thenReturn(session);
        when(session.run(anyString(), anyMap())).thenReturn(result);
        when(result.hasNext()).thenReturn(false);

        CaseDataFetcher fetcher = new CaseDataFetcher(driver);
        Object fetched = fetcher.get(env);

        assertInstanceOf(List.class, fetched);
        List<?> asList = (List<?>) fetched;
        assertTrue(asList.isEmpty());
    }
}
