#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// Minimal Objective-C wrapper over the ONNX Runtime C API with the Core ML execution provider, exposing
/// a single all-MiniLM embed call. Lives in-tree (not a pod) so the app needs only the vendored
/// onnxruntime.xcframework. Mean-pools over the attention mask and L2-normalizes — identical math to the
/// engine's C# OnnxTextEncoder, so query/corpus vectors are comparable across runtimes.
@interface EvsOrtSession : NSObject

/// Creates a session for `modelPath` with the Core ML EP enabled. Returns nil + `error` on failure.
- (nullable instancetype)initWithModelPath:(NSString *)modelPath error:(NSError **)error;

/// Embeds the tokenized input (length `length`) into `out` (caller supplies 384 floats). NO on failure.
- (BOOL)embedInputIds:(const int64_t *)ids
        attentionMask:(const int64_t *)mask
               length:(NSInteger)length
                  out:(float *)out
                error:(NSError **)error
    NS_SWIFT_NAME(embed(inputIds:attentionMask:length:out:));
@end

NS_ASSUME_NONNULL_END
