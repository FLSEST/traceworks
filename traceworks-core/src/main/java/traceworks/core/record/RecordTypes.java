package traceworks.core.record;

/** The event types of the run record. */
public final class RecordTypes {

    public static final String RUN_STARTED = "run.started";
    public static final String STEP_STARTED = "step.started";
    public static final String STEP_WAITING = "step.waiting";
    public static final String STEP_COMMAND = "step.command";
    public static final String STEP_COMMAND_FINISHED = "step.command.finished";
    public static final String AI_REQUEST = "ai.request";
    public static final String AI_TOOL_CALL = "ai.tool_call";
    public static final String AI_RESPONSE = "ai.response";
    public static final String STEP_OUTPUT = "step.output";
    public static final String GATE_REPORTED = "gate.reported";
    public static final String APPROVAL_REQUESTED = "approval.requested";
    public static final String APPROVAL_ANSWERED = "approval.answered";
    public static final String REQUEST_REFUSED = "request.refused";
    public static final String FOREACH_ITEM_STARTED = "foreach.item.started";
    public static final String FOREACH_ITEM_FINISHED = "foreach.item.finished";
    public static final String AGENTIC_PROGRESS = "agentic.progress";
    public static final String STEP_FILES = "step.files";
    public static final String RUN_STATUS = "run.status";
    public static final String RUN_CHECKPOINT = "run.checkpoint";
    public static final String STEP_FINISHED = "step.finished";
    public static final String RUN_PARKED = "run.parked";
    public static final String RUN_RESUMED = "run.resumed";
    public static final String RUN_CANCEL_REQUESTED = "run.cancel_requested";
    public static final String RUN_PAUSE_REQUESTED = "run.pause_requested";
    public static final String RUN_RECOVERED = "run.recovered";
    public static final String RUN_FINISHED = "run.finished";

    private RecordTypes() {
    }
}
