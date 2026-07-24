$ErrorActionPreference = "Stop"

$candelaRoot = $PSScriptRoot

Push-Location $candelaRoot
try {
	.\gradlew.bat --stop
	$env:JAVA_TOOL_OPTIONS = '-Xmx8G -XX:+UseCompactObjectHeaders -XX:+AlwaysPreTouch -XX:+UseStringDeduplication -XX:+UseZGC -Dcaustica.rt.safe.singleQueue=true -Dcaustica.rt.safe.destroyMarginFrames=8 -Dcaustica.rt.safe.noPushRing=true -Dcaustica.rt.safe.noTlasRing=true -Dcaustica.rt.safe.noNullBda=true -Dcaustica.rt.safe.noPipelineOptimization=false'
	.\gradlew.bat runClient --args="--renderDebugLabels --graphicsBackend VULKAN"
}
finally {
	Pop-Location
}
