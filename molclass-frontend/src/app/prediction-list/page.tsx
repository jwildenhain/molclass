"use client";

import { useEffect, useState } from "react";

interface ModelJob {
  model_id: number;
  name: string;
  username: string;
  batch_id: number;
  data_type: string;
  class_scheme: string;
  feature_selection: string;
  is_built: number;
}

export default function PredictionListPage() {
  const [models, setModels] = useState<ModelJob[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetch("/api/models")
      .then((res) => res.json())
      .then((data) => {
        setModels(data);
        setLoading(false);
      })
      .catch((err) => {
        console.error("Failed to fetch models:", err);
        setLoading(false);
      });
  }, []);

  return (
    <div className="max-w-7xl mx-auto mt-12 space-y-8">
      <div className="flex justify-between items-center">
        <div>
          <h1 className="text-3xl font-bold text-slate-100">Prediction List & Models</h1>
          <p className="text-slate-400 mt-2">Monitor the status of your machine learning models and view predictions.</p>
        </div>
      </div>

      <div className="bg-slate-900/50 backdrop-blur-md rounded-2xl border border-slate-800 shadow-2xl overflow-hidden">
        {loading ? (
          <div className="p-12 text-center text-slate-400 animate-pulse">Loading model data...</div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left border-collapse">
              <thead>
                <tr className="bg-slate-800/50 border-b border-slate-700/50">
                  <th className="p-4 text-slate-300 font-semibold">Model ID</th>
                  <th className="p-4 text-slate-300 font-semibold">Name</th>
                  <th className="p-4 text-slate-300 font-semibold">Algorithm</th>
                  <th className="p-4 text-slate-300 font-semibold">Feature Selection</th>
                  <th className="p-4 text-slate-300 font-semibold">Data Type</th>
                  <th className="p-4 text-slate-300 font-semibold">Source Batch</th>
                  <th className="p-4 text-slate-300 font-semibold">Status</th>
                  <th className="p-4 text-slate-300 font-semibold">Action</th>
                </tr>
              </thead>
              <tbody>
                {models.length === 0 ? (
                  <tr>
                    <td colSpan={8} className="p-8 text-center text-slate-500">No models found in the database.</td>
                  </tr>
                ) : (
                  models.map((model) => (
                    <tr key={model.model_id} className="border-b border-slate-800/50 hover:bg-slate-800/30 transition-colors">
                      <td className="p-4 text-slate-400 font-mono text-sm">{model.model_id}</td>
                      <td className="p-4 text-slate-200">{model.name}</td>
                      <td className="p-4 text-slate-400">{model.class_scheme}</td>
                      <td className="p-4 text-purple-400 text-sm">{model.feature_selection || 'CfsSubsetEval'}</td>
                      <td className="p-4 text-slate-400">
                        <span className="px-2 py-1 bg-slate-800 text-slate-300 rounded text-xs">{model.data_type}</span>
                      </td>
                      <td className="p-4 text-slate-400 font-mono text-sm">{String(model.batch_id).padStart(11, '0')}</td>
                      <td className="p-4">
                        {model.is_built === 1 ? (
                          <span className="px-3 py-1 bg-emerald-500/20 text-emerald-400 text-xs font-medium rounded-full border border-emerald-500/30">Trained</span>
                        ) : (
                          <span className="px-3 py-1 bg-amber-500/20 text-amber-400 text-xs font-medium rounded-full border border-amber-500/30">Queued</span>
                        )}
                      </td>
                      <td className="p-4">
                        <button 
                          disabled={model.is_built === 0}
                          onClick={() => window.location.href = `/prediction-list/${model.model_id}`}
                          className="px-4 py-2 bg-slate-700 hover:bg-slate-600 disabled:opacity-50 disabled:cursor-not-allowed text-white text-sm font-medium rounded-lg transition-colors inline-block"
                        >
                          View Details
                        </button>
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}
