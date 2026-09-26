module fill_stable_planes;
import caustica_api;
import path_tracer;
import sharc_policy;
import retained_trace_ordinary;
[shader("raygeneration")]
void main<TComposition : IComposition>() {
    runFillStablePlanes<TComposition, RetainedOrdinaryTrace, SharcDisabled>();
}
