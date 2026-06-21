"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";

export default function UploadPage() {
  const [file, setFile] = useState<File | null>(null);
  const [uploading, setUploading] = useState(false);
  const [message, setMessage] = useState("");
  const router = useRouter();

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (e.target.files && e.target.files.length > 0) {
      setFile(e.target.files[0]);
    }
  };

  const handleDrop = (e: React.DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    if (e.dataTransfer.files && e.dataTransfer.files.length > 0) {
      setFile(e.dataTransfer.files[0]);
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!file) return;

    setUploading(true);
    setMessage("");

    const formData = new FormData();
    formData.append("file", file);

    try {
      const res = await fetch("/api/upload", {
        method: "POST",
        body: formData,
      });

      if (res.ok) {
        setMessage("File successfully uploaded and queued for processing!");
        setFile(null);
        setTimeout(() => router.push("/model-creation"), 2000);
      } else {
        setMessage("Upload failed. Please try again.");
      }
    } catch (err) {
      setMessage("Error connecting to server.");
    } finally {
      setUploading(false);
    }
  };

  return (
    <div className="max-w-2xl mx-auto mt-12 p-8 bg-slate-900/50 backdrop-blur-md rounded-2xl border border-slate-800 shadow-2xl">
      <h1 className="text-3xl font-bold text-slate-100 mb-6">Upload SDF Dataset</h1>
      <p className="text-slate-400 mb-8">
        Import your chemical structures (.sdf) to extract their topological descriptors and molecular fingerprints into the database.
      </p>

      <form onSubmit={handleSubmit} className="space-y-6">
        <div 
          className="border-2 border-dashed border-slate-700 hover:border-blue-500 transition-colors rounded-xl p-12 text-center cursor-pointer bg-slate-800/30"
          onDragOver={(e) => e.preventDefault()}
          onDrop={handleDrop}
          onClick={() => document.getElementById("file-upload")?.click()}
        >
          <input 
            type="file" 
            id="file-upload" 
            className="hidden" 
            accept=".sdf"
            onChange={handleFileChange} 
          />
          <div className="flex flex-col items-center justify-center space-y-4">
            <svg className="w-12 h-12 text-slate-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12" />
            </svg>
            <span className="text-slate-300 font-medium">
              {file ? file.name : "Click to browse or drag and drop your .sdf file here"}
            </span>
          </div>
        </div>

        <button 
          type="submit" 
          disabled={!file || uploading}
          className="w-full py-3 px-4 bg-gradient-to-r from-blue-600 to-indigo-600 hover:from-blue-500 hover:to-indigo-500 text-white font-bold rounded-lg shadow-lg disabled:opacity-50 disabled:cursor-not-allowed transition-all"
        >
          {uploading ? "Uploading..." : "Submit Dataset"}
        </button>

        {message && (
          <div className={`p-4 rounded-lg text-center ${message.includes("success") ? "bg-emerald-900/50 text-emerald-400" : "bg-red-900/50 text-red-400"}`}>
            {message}
          </div>
        )}
      </form>
    </div>
  );
}
