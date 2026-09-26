module fill_sharc_query_ser;
import caustica_api;
import path_tracer;
import retained_trace_reordered;
import sharc_bridge;
[shader("raygeneration")]
void main<TComposition : IComposition>() {
    runFillStablePlanes<TComposition, RetainedReorderedTrace, SharcQueryPolicy>();
}
