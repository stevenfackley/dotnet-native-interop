// Gate: does ML.NET (a reflection-emit-heavy, dynamic-schema framework) publish + run under
// NativeAOT? EXPECTED to be hostile: ML.NET builds its data pipeline via runtime IL emit and
// reflection over generic type parameters, neither of which NativeAOT/trimming supports well.
// If publish or run fails, that IS the deliverable.
// See docs/mlnet-nativeaot-findings.md.
using Microsoft.ML;
using Microsoft.ML.Data;

var mlContext = new MLContext(seed: 1);

// Tiny synthetic "is the prompt a maintenance question" binary classifier.
TrainingExample[] data =
[
    new TrainingExample { WordCount = 3, HasQuestionMark = 1, Label = true },
    new TrainingExample { WordCount = 12, HasQuestionMark = 1, Label = true },
    new TrainingExample { WordCount = 20, HasQuestionMark = 1, Label = true },
    new TrainingExample { WordCount = 2, HasQuestionMark = 0, Label = false },
    new TrainingExample { WordCount = 5, HasQuestionMark = 0, Label = false },
    new TrainingExample { WordCount = 1, HasQuestionMark = 0, Label = false },
];

IDataView trainingData = mlContext.Data.LoadFromEnumerable(data);

var pipeline = mlContext.Transforms.Concatenate("Features", nameof(TrainingExample.WordCount), nameof(TrainingExample.HasQuestionMark))
    .Append(mlContext.BinaryClassification.Trainers.SdcaLogisticRegression());

Console.WriteLine("Training SDCA logistic regression on 6 in-memory rows...");
ITransformer model = pipeline.Fit(trainingData);
Console.WriteLine("Training completed.");

var predictionEngine = mlContext.Model.CreatePredictionEngine<TrainingExample, Prediction>(model);
Prediction prediction = predictionEngine.Predict(new TrainingExample { WordCount = 9, HasQuestionMark = 1 });

Console.WriteLine($"Predict: PredictedLabel={prediction.PredictedLabel} Probability={prediction.Probability:F3}");
Console.WriteLine("PASS: ML.NET trained and predicted under NativeAOT");

internal sealed class TrainingExample
{
    public float WordCount { get; set; }
    public float HasQuestionMark { get; set; }
    public bool Label { get; set; }
}

internal sealed class Prediction
{
    [ColumnName("PredictedLabel")]
    public bool PredictedLabel { get; set; }

    public float Probability { get; set; }
}
