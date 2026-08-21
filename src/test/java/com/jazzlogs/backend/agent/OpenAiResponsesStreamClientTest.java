package com.jazzlogs.backend.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.core.http.StreamResponse;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCompletedEvent;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseErrorEvent;
import com.openai.models.responses.ResponseFailedEvent;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseOutputText;
import com.openai.models.responses.ResponseStatus;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.ToolChoiceOptions;
import com.openai.services.blocking.ResponseService;

// Pure Mockito unit tests — no real network I/O. The lazy OpenAIClient field
// is replaced directly via ReflectionTestUtils, so most tests never go near
// the real client()/OpenAIOkHttpClient path at all; the two tests that do
// (blankApiKey_..., lazyClientInit_...) exercise that path deliberately and
// build their own OpenAiResponsesStreamClient instance to do it. Response/
// event fixtures are built through the SDK's own builders rather than mocked
// — these are plain data classes with required-field validation, so a
// hand-built "minimal valid" fixture is more honest than stubbing accessors.
@ExtendWith(MockitoExtension.class)
class OpenAiResponsesStreamClientTest {

    @Mock
    private OpenAIClient mockClient;

    @Mock
    private ResponseService responseService;

    private OpenAiResponsesStreamClient client;

    @BeforeEach
    void setUp() {
        client = new OpenAiResponsesStreamClient("sk-test-key", "gpt-5.6-luna", List.of());
        ReflectionTestUtils.setField(client, "client", mockClient);
    }

    @Test
    void everyCall_sendsParallelToolCallsAndTheFinalAnswerSchema() throws Exception {
        stubStreamWith(completedEvent(minimalResponse("resp_1", List.of())));

        client.streamTurn(List.of(), null, false);

        ResponseCreateParams params = capturedParams();
        assertThat(params.parallelToolCalls()).contains(true);
        assertThat(params.text()).isPresent();
    }

    @Test
    void toolChoiceIsAuto_whenNotForcingAFinalAnswer() throws Exception {
        stubStreamWith(completedEvent(minimalResponse("resp_1", List.of())));

        client.streamTurn(List.of(), null, false);

        assertThat(toolChoiceOf(capturedParams())).contains(ToolChoiceOptions.AUTO);
    }

    @Test
    void toolChoiceIsForcedToNone_onTheForcedFinalIteration() throws Exception {
        stubStreamWith(completedEvent(minimalResponse("resp_1", List.of())));

        client.streamTurn(List.of(), null, true);

        assertThat(toolChoiceOf(capturedParams())).contains(ToolChoiceOptions.NONE);
    }

    @Test
    void omitsPreviousResponseId_onTheFirstCallOfAnExchange() throws Exception {
        stubStreamWith(completedEvent(minimalResponse("resp_1", List.of())));

        client.streamTurn(List.of(), null, false);

        assertThat(capturedParams().previousResponseId()).isEmpty();
    }

    @Test
    void setsPreviousResponseId_whenChainingFromAPriorTurn() throws Exception {
        stubStreamWith(completedEvent(minimalResponse("resp_2", List.of())));

        client.streamTurn(List.of(), "resp_1", false);

        assertThat(capturedParams().previousResponseId()).contains("resp_1");
    }

    @Test
    void reducesACompletedResponse_intoTextAndToolCalls() throws Exception {
        Response response = minimalResponse("resp_1", List.of(
            messageItem("hello"),
            functionCallItem("call_1", "semantic_catalog_search", "{}")
        ));
        stubStreamWith(completedEvent(response));

        Step step = client.streamTurn(List.of(), null, false);

        assertThat(step.responseId()).isEqualTo("resp_1");
        assertThat(step.assistantText()).isEqualTo("hello");
        assertThat(step.toolCalls()).hasSize(1);
        assertThat(step.toolCalls().get(0).callId()).isEqualTo("call_1");
    }

    @Test
    void completedResponseWithNoOutput_reducesToBlankTextAndNoToolCalls() throws Exception {
        stubStreamWith(completedEvent(minimalResponse("resp_1", List.of())));

        Step step = client.streamTurn(List.of(), null, false);

        assertThat(step.assistantText()).isEmpty();
        assertThat(step.toolCalls()).isEmpty();
    }

    @Test
    void streamFailedEvent_throwsBeforeReturningAStep() {
        stubStreamWith(failedEvent(minimalResponse("resp_1", List.of())));

        assertThatThrownBy(() -> client.streamTurn(List.of(), null, false))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Responses API stream failed");
    }

    @Test
    void streamErrorEvent_throwsBeforeReturningAStep() {
        stubStreamWith(errorEvent("boom"));

        assertThatThrownBy(() -> client.streamTurn(List.of(), null, false))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("boom");
    }

    @Test
    void streamEndingWithoutACompletedEvent_throws() {
        when(mockClient.responses()).thenReturn(responseService);
        when(responseService.createStreaming(any(ResponseCreateParams.class))).thenReturn(fakeStream(List.of()));

        assertThatThrownBy(() -> client.streamTurn(List.of(), null, false))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("without a completed event");
    }

    @Test
    void blankApiKey_throwsInsteadOfBuildingAClient() {
        OpenAiResponsesStreamClient noKeyClient = new OpenAiResponsesStreamClient("", "gpt-5.6-luna", List.of());

        assertThatThrownBy(() -> noKeyClient.streamTurn(List.of(), null, false))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("OPENAI_API_KEY");
    }

    // Races many threads through client() on a single fresh instance (real
    // apiKey, no mock — this exercises the actual OpenAIOkHttpClient.build()
    // path) and asserts every thread landed on the exact same instance, i.e.
    // the double-checked lock never let two threads each build their own.
    @Test
    void lazyClientInit_isThreadSafeUnderConcurrentFirstAccess() throws Exception {
        OpenAiResponsesStreamClient freshClient = new OpenAiResponsesStreamClient("sk-fake-test-key", "gpt-5.6-luna", List.of());
        int threadCount = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                futures.add(pool.submit(() -> ReflectionTestUtils.invokeMethod(freshClient, "client")));
            }
            Set<Object> distinctClients = new HashSet<>();
            for (Future<Object> future : futures) {
                distinctClients.add(future.get());
            }
            assertThat(distinctClients).hasSize(1);
        } finally {
            pool.shutdown();
        }
    }

    private void stubStreamWith(ResponseStreamEvent... events) {
        when(mockClient.responses()).thenReturn(responseService);
        when(responseService.createStreaming(any(ResponseCreateParams.class))).thenReturn(fakeStream(List.of(events)));
    }

    private ResponseCreateParams capturedParams() {
        ArgumentCaptor<ResponseCreateParams> captor = ArgumentCaptor.forClass(ResponseCreateParams.class);
        verify(responseService).createStreaming(captor.capture());
        return captor.getValue();
    }

    private static Optional<ToolChoiceOptions> toolChoiceOf(ResponseCreateParams params) {
        return params.toolChoice().flatMap(ResponseCreateParams.ToolChoice::options);
    }

    private static StreamResponse<ResponseStreamEvent> fakeStream(List<ResponseStreamEvent> events) {
        return new StreamResponse<>() {
            @Override
            public Stream<ResponseStreamEvent> stream() {
                return events.stream();
            }

            @Override
            public void close() {
            }
        };
    }

    private static ResponseStreamEvent completedEvent(Response response) {
        return ResponseStreamEvent.ofCompleted(ResponseCompletedEvent.builder().response(response).sequenceNumber(0).build());
    }

    private static ResponseStreamEvent failedEvent(Response response) {
        return ResponseStreamEvent.ofFailed(ResponseFailedEvent.builder().response(response).sequenceNumber(0).build());
    }

    private static ResponseStreamEvent errorEvent(String message) {
        return ResponseStreamEvent.ofError(
            ResponseErrorEvent.builder().message(message).code(Optional.empty()).param(Optional.empty()).sequenceNumber(0).build()
        );
    }

    private static ResponseOutputItem messageItem(String text) {
        return ResponseOutputItem.ofMessage(
            ResponseOutputMessage.builder()
                .id("msg_1")
                .content(List.of(ResponseOutputMessage.Content.ofOutputText(
                    ResponseOutputText.builder().text(text).annotations(List.of()).build()
                )))
                .role(JsonValue.from("assistant"))
                .status(ResponseOutputMessage.Status.COMPLETED)
                .build()
        );
    }

    private static ResponseOutputItem functionCallItem(String callId, String name, String argumentsJson) {
        return ResponseOutputItem.ofFunctionCall(
            ResponseFunctionToolCall.builder().callId(callId).name(name).arguments(argumentsJson).build()
        );
    }

    // The minimum set of fields Response.Builder.build() requires — found by
    // adding fields until the required-field check stopped throwing, not
    // documented anywhere. None of these values matter to what's under test.
    private static Response minimalResponse(String id, List<ResponseOutputItem> output) {
        return Response.builder()
            .id(id)
            .output(output)
            .createdAt(0.0)
            .model("gpt-5.6-luna")
            .parallelToolCalls(true)
            .toolChoice(ToolChoiceOptions.AUTO)
            .tools(List.of())
            .topP(1.0)
            .temperature(1.0)
            .status(ResponseStatus.COMPLETED)
            .error(Optional.empty())
            .incompleteDetails(Optional.empty())
            .instructions(Optional.empty())
            .metadata(Optional.empty())
            .build();
    }
}
