"use client";

import { useState, Suspense } from "react";
import { useRouter, useSearchParams } from "next/navigation";

function ConfigureModelForm() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const batchId = searchParams.get("batch_id") || "00000000000";

  const [submitting, setSubmitting] = useState(false);
  const [message, setMessage] = useState("");

  const [formData, setFormData] = useState({
    data_type: "ALL",
    class_scheme: "RandomForest",
    feature_selection: "CfsSubsetEval",
    class_tag: "class",
    email: "root@localhost.org"
  });

  const handleChange = (e: React.ChangeEvent<HTMLSelectElement | HTMLInputElement>) => {
    setFormData({ ...formData, [e.target.name]: e.target.value });
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    setMessage("");

    const params = new URLSearchParams({
      batch_id: parseInt(batchId, 10).toString(),
      data_type: formData.data_type,
      class_scheme: formData.class_scheme,
      feature_selection: formData.feature_selection,
      class_tag: formData.class_tag,
      email: formData.email
    });

    try {
      const res = await fetch("/api/models/queue", {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: params.toString()
      });

      if (res.ok) {
        setMessage("Model building job successfully queued!");
        setTimeout(() => router.push("/prediction-list"), 2000);
      } else {
        setMessage("Failed to queue model. Please check settings.");
      }
    } catch (err) {
      setMessage("Server connection error.");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <>
      <p className="text-slate-400 mb-8 font-mono text-sm">Target Batch: {batchId}</p>

      <form onSubmit={handleSubmit} className="space-y-6">
        
        {/* Data Type */}
        <div className="space-y-2">
          <label className="text-sm font-medium text-slate-300">Data Type / Descriptors</label>
          <select 
            name="data_type" 
            value={formData.data_type} 
            onChange={handleChange}
            className="w-full bg-slate-800 border border-slate-700 text-slate-200 rounded-lg p-3 focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500 outline-none transition-all"
          >
            {["CDK", "MACCS", "ALL", "PubChem", "EXT", "EXTGO", "KR", "SUB", "JUMBO", "MCAT", "GO"].map(dt => (
              <option key={dt} value={dt}>{dt}</option>
            ))}
          </select>
        </div>

        {/* Class Scheme */}
        <div className="space-y-2">
          <label className="text-sm font-medium text-slate-300">Algorithm (Class Scheme)</label>
          <select 
            name="class_scheme" 
            value={formData.class_scheme} 
            onChange={handleChange}
            className="w-full bg-slate-800 border border-slate-700 text-slate-200 rounded-lg p-3 focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500 outline-none transition-all"
          >
            {["RandomForest", "LMT", "J48", "NaiveBayes", "KNN", "SMO", "LibSVM", "LogitBoost", "RacedIncrementalLogitBoost", "Ensemble", "NBTree", "HiddenNaiveBayes", "DecisionTreeNaiveBayes", "LibSVM2", "BayesNet", "NeuralNet", "Ensemble2"].map(cs => (
              <option key={cs} value={cs}>{cs}</option>
            ))}
          </select>
        </div>

        {/* Feature Selection */}
        <div className="space-y-2">
          <label className="text-sm font-medium text-slate-300">Feature Selection Optimizer</label>
          <select 
            name="feature_selection" 
            value={formData.feature_selection} 
            onChange={handleChange}
            className="w-full bg-slate-800 border border-slate-700 text-slate-200 rounded-lg p-3 focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500 outline-none transition-all"
          >
            <option value="CfsSubsetEval">Correlation-Based (CFS Subset) - Legacy Default</option>
            <option value="ReliefFAttributeEval">Relief-F (High Speed, Noise Tolerant)</option>
            <option value="None">None (Train on all raw features)</option>
          </select>
        </div>

        {/* Classifier Tag */}
        <div className="space-y-2">
          <label className="text-sm font-medium text-slate-300">Target Feature</label>
          <select 
            name="class_tag" 
            value={formData.class_tag} 
            onChange={handleChange}
            className="w-full bg-slate-800 border border-slate-700 text-slate-200 rounded-lg p-3 focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500 outline-none transition-all"
          >
            <option value="class">class</option>
          </select>
        </div>

        {/* Email */}
        <div className="space-y-2">
          <label className="text-sm font-medium text-slate-300">Notification Email</label>
          <input 
            type="email" 
            name="email" 
            value={formData.email} 
            onChange={handleChange}
            className="w-full bg-slate-800 border border-slate-700 text-slate-200 rounded-lg p-3 focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500 outline-none transition-all"
            required
          />
        </div>

        <button 
          type="submit" 
          disabled={submitting}
          className="w-full py-3 px-4 bg-gradient-to-r from-indigo-600 to-purple-600 hover:from-indigo-500 hover:to-purple-500 text-white font-bold rounded-lg shadow-lg disabled:opacity-50 transition-all mt-4"
        >
          {submitting ? "Queueing Job..." : "Confirm & Queue Job"}
        </button>

        {message && (
          <div className={`p-4 rounded-lg text-center ${message.includes("success") ? "bg-emerald-900/50 text-emerald-400" : "bg-red-900/50 text-red-400"}`}>
            {message}
          </div>
        )}
      </form>
    </>
  );
}

export default function ConfigureModelPage() {
  return (
    <div className="max-w-2xl mx-auto mt-12 p-8 bg-slate-900/50 backdrop-blur-md rounded-2xl border border-slate-800 shadow-2xl">
      <h1 className="text-3xl font-bold text-slate-100 mb-2">Configure Training Job</h1>
      <Suspense fallback={<p className="text-slate-400">Loading Configuration...</p>}>
        <ConfigureModelForm />
      </Suspense>
    </div>
  );
}
