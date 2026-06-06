/*
 * Objective-C bridging header — exposes the NativeAOT C ABI to Swift.
 * The dni.xcframework is linked at build time; these symbols are
 * resolved at link, not via dlopen.
 */
#import "../../../core/DotnetNativeInterop.NativeBridge/abi/dni.h"

/* EVS: the Objective-C ONNX Runtime + Core ML wrapper, used by EdgeSearchEngine (Swift). */
#import "../../Shared/EdgeSearch/EvsOrt.h"
