#import "EvsOrt.h"
#import <onnxruntime/onnxruntime_c_api.h>
#import <onnxruntime/coreml_provider_factory.h>
#import <math.h>

@implementation EvsOrtSession {
    const OrtApi *_ort;
    OrtEnv *_env;
    OrtSession *_session;
    OrtMemoryInfo *_mem;
    NSString *_outputName;
}

- (nullable instancetype)initWithModelPath:(NSString *)modelPath error:(NSError **)error {
    self = [super init];
    if (!self) return nil;
    _ort = OrtGetApiBase()->GetApi(ORT_API_VERSION);

    if ([self fail:_ort->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "evs", &_env) error:error]) return nil;

    OrtSessionOptions *opts = NULL;
    if ([self fail:_ort->CreateSessionOptions(&opts) error:error]) return nil;
    // flags 0 = default Core ML behaviour (ANE/GPU/CPU as available).
    if ([self fail:OrtSessionOptionsAppendExecutionProvider_CoreML(opts, 0) error:error]) {
        _ort->ReleaseSessionOptions(opts); return nil;
    }
    OrtStatus *st = _ort->CreateSession(_env, modelPath.UTF8String, opts, &_session);
    _ort->ReleaseSessionOptions(opts);
    if ([self fail:st error:error]) return nil;

    if ([self fail:_ort->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &_mem) error:error]) return nil;

    OrtAllocator *alloc = NULL;
    _ort->GetAllocatorWithDefaultOptions(&alloc);
    char *outName = NULL;
    if ([self fail:_ort->SessionGetOutputName(_session, 0, alloc, &outName) error:error]) return nil;
    _outputName = [NSString stringWithUTF8String:outName];
    alloc->Free(alloc, outName);
    return self;
}

- (BOOL)embedInputIds:(const int64_t *)ids attentionMask:(const int64_t *)mask
               length:(NSInteger)length out:(float *)out error:(NSError **)error {
    int64_t shape[2] = {1, (int64_t)length};
    size_t bytes = sizeof(int64_t) * (size_t)length;
    int64_t *types = (int64_t *)calloc((size_t)length, sizeof(int64_t)); // token_type_ids = 0
    OrtValue *idsVal = NULL, *maskVal = NULL, *typeVal = NULL, *outVal = NULL;

    BOOL bad = [self fail:_ort->CreateTensorWithDataAsOrtValue(_mem, (void *)ids, bytes, shape, 2,
                    ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &idsVal) error:error]
        || [self fail:_ort->CreateTensorWithDataAsOrtValue(_mem, (void *)mask, bytes, shape, 2,
                    ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &maskVal) error:error]
        || [self fail:_ort->CreateTensorWithDataAsOrtValue(_mem, types, bytes, shape, 2,
                    ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &typeVal) error:error];
    if (bad) { free(types); return NO; }

    const char *inNames[3] = {"input_ids", "attention_mask", "token_type_ids"};
    const OrtValue *inVals[3] = {idsVal, maskVal, typeVal};
    const char *outNames[1] = {_outputName.UTF8String};
    OrtStatus *st = _ort->Run(_session, NULL, inNames, inVals, 3, outNames, 1, &outVal);
    _ort->ReleaseValue(idsVal); _ort->ReleaseValue(maskVal); _ort->ReleaseValue(typeVal); free(types);
    if ([self fail:st error:error]) return NO;

    float *hidden = NULL; // [1, length, 384]
    if ([self fail:_ort->GetTensorMutableData(outVal, (void **)&hidden) error:error]) {
        _ort->ReleaseValue(outVal); return NO;
    }
    const NSInteger dim = 384;
    for (NSInteger d = 0; d < dim; d++) out[d] = 0.0f;
    int64_t count = 0;
    for (NSInteger t = 0; t < length; t++) {
        if (mask[t] == 0) continue;
        count++;
        const float *row = hidden + (t * dim);
        for (NSInteger d = 0; d < dim; d++) out[d] += row[d];
    }
    if (count > 0) for (NSInteger d = 0; d < dim; d++) out[d] /= (float)count;
    float norm = 0.0f; for (NSInteger d = 0; d < dim; d++) norm += out[d] * out[d];
    norm = sqrtf(norm);
    if (norm > 0) for (NSInteger d = 0; d < dim; d++) out[d] /= norm;

    _ort->ReleaseValue(outVal);
    return YES;
}

// Returns YES (and fills *error) when `status` is a real error; releases it. NULL status -> NO.
- (BOOL)fail:(OrtStatus *)status error:(NSError **)error {
    if (status == NULL) return NO;
    if (error) *error = [NSError errorWithDomain:@"EvsOrt" code:1
        userInfo:@{NSLocalizedDescriptionKey: @(_ort->GetErrorMessage(status))}];
    _ort->ReleaseStatus(status);
    return YES;
}
@end
