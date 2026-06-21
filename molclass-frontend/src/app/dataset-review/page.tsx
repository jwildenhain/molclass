"use client";

import { useEffect, useState } from "react";

interface DatasetReview {
  batch_id: number;
  name: string;
  description: string;
}

export default function DatasetReviewPage() {
  const [reviews, setReviews] = useState<DatasetReview[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetch("/api/dataset-reviews")
      .then((res) => res.json())
      .then((data) => {
        setReviews(data);
        setLoading(false);
      })
      .catch((err) => {
        console.error("Failed to fetch dataset reviews:", err);
        setLoading(false);
      });
  }, []);

  return (
    <div className="max-w-7xl mx-auto mt-12 space-y-8">
      <div className="flex justify-between items-center">
        <div>
          <h1 className="text-3xl font-bold text-slate-100">Dataset Review</h1>
          <p className="text-slate-400 mt-2">Information extracted from the MolClass legacy wiki regarding current datasets.</p>
        </div>
      </div>

      <div className="bg-slate-900/50 backdrop-blur-md rounded-2xl border border-slate-800 shadow-2xl overflow-hidden">
        {loading ? (
          <div className="p-12 text-center text-slate-400 animate-pulse">Loading datasets...</div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left border-collapse">
              <thead>
                <tr className="bg-slate-800/50 border-b border-slate-700/50">
                  <th className="p-4 text-slate-300 font-semibold w-24">Batch ID</th>
                  <th className="p-4 text-slate-300 font-semibold w-1/4">Name</th>
                  <th className="p-4 text-slate-300 font-semibold">Description</th>
                </tr>
              </thead>
              <tbody>
                {reviews.length === 0 ? (
                  <tr>
                    <td colSpan={3} className="p-8 text-center text-slate-500">No dataset reviews found.</td>
                  </tr>
                ) : (
                  reviews.map((review) => (
                    <tr key={review.batch_id} className="border-b border-slate-800/50 hover:bg-slate-800/30 transition-colors">
                      <td className="p-4 text-slate-400 font-mono text-sm align-top">{String(review.batch_id).padStart(11, '0')}</td>
                      <td className="p-4 text-slate-200 font-semibold align-top">{review.name}</td>
                      <td className="p-4 text-slate-400 text-sm whitespace-pre-line leading-relaxed align-top">
                        {review.description}
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
