"use client";

import { useEffect, useState, use } from "react";
import Link from "next/link";

interface PredictionResult {
  mol_id: number;
  main_class: string;
  distribution: string;
  response_strength: number;
  certainty_score: number;
}

export default function PredictionDetailsPage({ params }: { params: Promise<{ id: string }> }) {
  const unwrappedParams = use(params);
  const { id } = unwrappedParams;
  const [results, setResults] = useState<PredictionResult[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetch(`/api/predictions/${id}/results`)
      .then((res) => res.json())
      .then((data) => {
        setResults(data);
        setLoading(false);
      })
      .catch((err) => {
        console.error("Failed to fetch prediction results:", err);
        setLoading(false);
      });
  }, [id]);

  return (
    <div className="max-w-7xl mx-auto mt-12 space-y-8">
      <div className="flex justify-between items-center">
        <div>
          <Link href="/prediction-list" className="text-purple-400 hover:text-purple-300 text-sm font-medium mb-4 inline-block">&larr; Back to Models</Link>
          <h1 className="text-3xl font-bold text-slate-100">Prediction Results for Model #{id}</h1>
          <p className="text-slate-400 mt-2">View the response strength and certainty score (Applicability Domain) for predicted molecules.</p>
        </div>
      </div>

      <div className="bg-slate-900/50 backdrop-blur-md rounded-2xl border border-slate-800 shadow-2xl overflow-hidden">
        {loading ? (
          <div className="p-12 text-center text-slate-400 animate-pulse">Loading prediction results...</div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left border-collapse">
              <thead>
                <tr className="bg-slate-800/50 border-b border-slate-700/50">
                  <th className="p-4 text-slate-300 font-semibold">Molecule ID</th>
                  <th className="p-4 text-slate-300 font-semibold">Predicted Class</th>
                  <th className="p-4 text-slate-300 font-semibold">Response Strength</th>
                  <th className="p-4 text-slate-300 font-semibold">Certainty (AD)</th>
                </tr>
              </thead>
              <tbody>
                {results.length === 0 ? (
                  <tr>
                    <td colSpan={4} className="p-8 text-center text-slate-500">No predictions found for this model.</td>
                  </tr>
                ) : (
                  results.map((res, index) => (
                    <tr key={`${res.mol_id}-${index}`} className="border-b border-slate-800/50 hover:bg-slate-800/30 transition-colors">
                      <td className="p-4 text-slate-400 font-mono text-sm">{res.mol_id}</td>
                      <td className="p-4 text-slate-200 font-medium">{res.main_class}</td>
                      <td className="p-4">
                        <div className="flex items-center space-x-2">
                          <span className="text-slate-200">{(res.response_strength * 100).toFixed(1)}%</span>
                          <div className="w-24 h-2 bg-slate-800 rounded-full overflow-hidden">
                            <div 
                              className="h-full bg-purple-500" 
                              style={{ width: `${res.response_strength * 100}%` }}
                            ></div>
                          </div>
                        </div>
                      </td>
                      <td className="p-4">
                        {res.certainty_score >= 0.7 ? (
                          <span className="px-3 py-1 bg-emerald-500/20 text-emerald-400 text-xs font-medium rounded-full border border-emerald-500/30">High ({res.certainty_score.toFixed(2)})</span>
                        ) : res.certainty_score >= 0.4 ? (
                          <span className="px-3 py-1 bg-amber-500/20 text-amber-400 text-xs font-medium rounded-full border border-amber-500/30">Medium ({res.certainty_score.toFixed(2)})</span>
                        ) : (
                          <span className="px-3 py-1 bg-red-500/20 text-red-400 text-xs font-medium rounded-full border border-red-500/30">Low ({res.certainty_score.toFixed(2)})</span>
                        )}
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
