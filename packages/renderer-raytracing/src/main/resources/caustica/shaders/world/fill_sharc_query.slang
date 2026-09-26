module fill_sharc_query;
import caustica_api;
import path_tracer;
import retained_trace_ordinary;
import sharc_bridge;
[shader("raygeneration")]
void main<TComposition : IComposition>() {
    runFillStablePlanes<TComposition, RetainedOrdinaryTrace, SharcQueryPolicy>();
}
