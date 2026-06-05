using Microsoft.ML.OnnxRuntime;
using Microsoft.ML.OnnxRuntime.Tensors;
using System.Numerics.Tensors;

// Disambiguate from the engine's own streaming DotnetNativeInterop.Engine.InferenceSession (same namespace).
using OrtSession = Microsoft.ML.OnnxRuntime.InferenceSession;

namespace DotnetNativeInterop.Engine;

/// <summary>
/// all-MiniLM-L6-v2 sentence encoder via ONNX Runtime: tokenize → run the transformer → mean-pool token
/// embeddings over the attention mask → L2-normalize to a 384-d vector. (Input/output names match the
/// model metadata confirmed by the feasibility spike.)
/// </summary>
public sealed class OnnxTextEncoder : ITextEncoder, IDisposable
{
    private const int MaxLen = 64;
    private readonly OrtSession _session;
    private readonly WordPieceTokenizer _tokenizer;
    private readonly string _outputName;

    public OnnxTextEncoder(string modelPath, WordPieceTokenizer tokenizer)
    {
        _session = new OrtSession(modelPath);
        _tokenizer = tokenizer;
        _outputName = _session.OutputMetadata.Keys.First();
    }

    public float[] Encode(string text)
    {
        var (ids, mask) = _tokenizer.Encode(text, MaxLen);
        var len = ids.Length;
        var inputs = new List<NamedOnnxValue>
        {
            NamedOnnxValue.CreateFromTensor("input_ids", new DenseTensor<long>(ids, [1, len])),
            NamedOnnxValue.CreateFromTensor("attention_mask", new DenseTensor<long>(mask, [1, len])),
            NamedOnnxValue.CreateFromTensor("token_type_ids", new DenseTensor<long>(new long[len], [1, len])),
        };

        using var results = _session.Run(inputs);
        var hidden = results.First(r => r.Name == _outputName).AsTensor<float>(); // [1, len, 384]
        var dim = hidden.Dimensions[2];

        // Mean-pool over masked tokens.
        var pooled = new float[dim];
        long count = 0;
        for (var t = 0; t < len; t++)
        {
            if (mask[t] == 0)
            {
                continue;
            }

            count++;
            for (var d = 0; d < dim; d++)
            {
                pooled[d] += hidden[0, t, d];
            }
        }

        if (count > 0)
        {
            for (var d = 0; d < dim; d++)
            {
                pooled[d] /= count;
            }
        }

        // L2-normalize so cosine == dot product.
        var norm = MathF.Sqrt(TensorPrimitives.Dot(pooled, pooled));
        if (norm > 0)
        {
            TensorPrimitives.Divide(pooled, norm, pooled);
        }

        return pooled;
    }

    public void Dispose() => _session.Dispose();
}
