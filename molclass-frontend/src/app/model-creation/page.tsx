"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";

interface Batch {
  batch_id: number;
  username: string;
  filename: string;
  info: string;
  uploaded: number;
}

export default function ModelCreationPage() {
  const [batches, setBatches] = useState<Batch[]>([]);
  const [loading, setLoading] = useState(true);
  const router = useRouter();

  useEffect(() => {
    // Fetch batches from the Spring Boot backend
    fetch("/api/batches")
      .then((res) => res.json())
      .then((data) => {
        setBatches(data);
        setLoading(false);
      })
      .catch((err) => {
        console.error("Failed to fetch batches:", err);
        setLoading(false);
      });
  }, []);

  return (
    <div className="max-w-6xl mx-auto mt-12 space-y-8">
      <div className="flex justify-between items-center">
        <div>
          <h1 className="text-3xl font-bold text-slate-100">Select Batch</h1>
          <p className="text-slate-400 mt-2">Choose an uploaded dataset batch to initiate machine learning model training.</p>
        </div>
      </div>

      <div className="bg-slate-900/50 backdrop-blur-md rounded-2xl border border-slate-800 shadow-2xl overflow-hidden">
        {loading ? (
          <div className="p-12 text-center text-slate-400 animate-pulse">Loading batches...</div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left border-collapse">
              <thead>
                <tr className="bg-slate-800/50 border-b border-slate-700/50">
                  <th className="p-4 text-slate-300 font-semibold">Batch ID</th>
                  <th className="p-4 text-slate-300 font-semibold">Filename</th>
                  <th className="p-4 text-slate-300 font-semibold">Username</th>
                  <th className="p-4 text-slate-300 font-semibold">Info</th>
                  <th className="p-4 text-slate-300 font-semibold">Action</th>
                </tr>
              </thead>
              <tbody>
                {batches.length === 0 ? (
                  <tr>
                    <td colSpan={5} className="p-8 text-center text-slate-500">No uploaded batches found.</td>
                  </tr>
                ) : (
                  batches.map((batch) => (
                    <tr key={batch.batch_id} className="border-b border-slate-800/50 hover:bg-slate-800/30 transition-colors">
                      <td className="p-4 text-slate-400 font-mono text-sm">{String(batch.batch_id).padStart(11, '0')}</td>
                      <td className="p-4 text-slate-200">{batch.filename}</td>
                      <td className="p-4 text-slate-400">{batch.username}</td>
                      <td className="p-4 text-slate-400 max-w-xs truncate">{batch.info || "-"}</td>
                      <td className="p-4">
                        <Link 
                          href={`/model-creation/configure?batch_id=${String(batch.batch_id).padStart(11, '0')}`}
                          className="px-4 py-2 bg-indigo-600 hover:bg-indigo-500 text-white text-sm font-medium rounded-lg transition-colors inline-block shadow-lg shadow-indigo-900/20"
                        >
                          Create Model
                        </Link>
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
