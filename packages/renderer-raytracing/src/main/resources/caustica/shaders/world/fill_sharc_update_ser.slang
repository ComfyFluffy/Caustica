module fill_sharc_update_ser;
import caustica_api;
import path_tracer;
import retained_trace_reordered;
import sharc_bridge;
[shader("raygeneration")]
void main<TComposition : IComposition>() {
    runFillStablePlanes<TComposition, RetainedReorderedTrace, SharcUpdatePolicy>();
}
