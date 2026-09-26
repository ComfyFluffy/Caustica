module fill_stable_planes_ser;
import caustica_api;
import path_tracer;
import sharc_policy;
import retained_trace_reordered;
[shader("raygeneration")]
void main<TComposition : IComposition>() {
    runFillStablePlanes<TComposition, RetainedReorderedTrace, SharcDisabled>();
}
