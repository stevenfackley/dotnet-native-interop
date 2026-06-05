/*
 * Objective-C bridging header — exposes the NativeAOT C ABI to Swift.
 * The ondevicellm.xcframework is linked at build time; these symbols are
 * resolved at link, not via dlopen.
 */
#import "../../../core/OnDeviceLlm.NativeBridge/abi/ondevicellm.h"
