"use client";

import { useState } from "react";

export default function StructureSearchPage() {
  const [searchQuery, setSearchQuery] = useState("");
  const [mode, setMode] = useState("1"); // 1=exact, 2=substructure, 3=similarity
  const [fsim, setFsim] = useState("0.5");
  const [strict, setStrict] = useState(false);
  const [stereo, setStereo] = useState(false);

  const [searching, setSearching] = useState(false);

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault();
    setSearching(true);
    // In a full implementation, this would hit the Spring Boot API
    setTimeout(() => {
      setSearching(false);
      alert("Structure search functionality will be connected to the CDK backend in the next phase.");
    }, 1000);
  };

  return (
    <div className="max-w-4xl mx-auto mt-12 space-y-8">
      <div className="text-center space-y-4">
        <h1 className="text-3xl font-bold text-slate-100">Structure Search</h1>
        <p className="text-slate-400">Search the database using exact matching, substructure patterns, or Klekota-Roth structural/functional similarity.</p>
      </div>

      <div className="bg-slate-900/50 backdrop-blur-md rounded-2xl border border-slate-800 shadow-2xl p-8">
        <form onSubmit={handleSearch} className="space-y-8">
          
          {/* Query Input */}
          <div className="space-y-2">
            <label className="text-sm font-medium text-slate-300">Search by InChI, InChIKey, or SMILES</label>
            <textarea 
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="w-full bg-slate-800 border border-slate-700 text-slate-200 rounded-lg p-4 focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500 outline-none transition-all min-h-[120px] font-mono text-sm"
              placeholder="InChI=1S/C9H8O4/c1-6(10)13-8-5-3-2-4-7(8)9(11)12..."
              required
            />
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-8">
            {/* Search Mode Options */}
            <div className="space-y-4 bg-slate-800/30 p-6 rounded-xl border border-slate-700/50">
              <h3 className="text-slate-200 font-semibold border-b border-slate-700 pb-2">Search Mode</h3>
              
              <label className="flex items-center space-x-3 cursor-pointer">
                <input type="radio" name="mode" value="1" checked={mode === "1"} onChange={(e) => setMode(e.target.value)} className="text-indigo-500 bg-slate-900 border-slate-700" />
                <span className="text-slate-300">Exact Search</span>
              </label>
              
              <label className="flex items-center space-x-3 cursor-pointer">
                <input type="radio" name="mode" value="2" checked={mode === "2"} onChange={(e) => setMode(e.target.value)} className="text-indigo-500 bg-slate-900 border-slate-700" />
                <span className="text-slate-300">Substructure Search</span>
              </label>

              <div className="space-y-2">
                <label className="flex items-center space-x-3 cursor-pointer">
                  <input type="radio" name="mode" value="3" checked={mode === "3"} onChange={(e) => setMode(e.target.value)} className="text-indigo-500 bg-slate-900 border-slate-700" />
                  <span className="text-slate-300">Similarity Search</span>
                </label>
                
                {mode === "3" && (
                  <div className="ml-8 mt-2 space-y-1">
                    <span className="text-xs text-slate-400 block mb-1">Structural:Functional Ratio</span>
                    <select 
                      value={fsim} 
                      onChange={(e) => setFsim(e.target.value)}
                      className="w-full bg-slate-900 border border-slate-700 text-slate-300 rounded p-2 text-sm outline-none focus:border-indigo-500"
                    >
                      <option value="0.0">100:0</option>
                      <option value="0.1">90:10</option>
                      <option value="0.2">80:20</option>
                      <option value="0.3">70:30</option>
                      <option value="0.4">60:40</option>
                      <option value="0.5">50:50</option>
                      <option value="0.6">40:60</option>
                      <option value="0.7">30:70</option>
                      <option value="0.8">20:80</option>
                      <option value="0.9">10:90</option>
                      <option value="1.0">0:100</option>
                    </select>
                  </div>
                )}
              </div>
            </div>

            {/* Constraints */}
            <div className="space-y-4 bg-slate-800/30 p-6 rounded-xl border border-slate-700/50">
              <h3 className="text-slate-200 font-semibold border-b border-slate-700 pb-2">Constraints</h3>
              
              <label className="flex items-center space-x-3 cursor-pointer mt-4">
                <input 
                  type="checkbox" 
                  checked={strict} 
                  onChange={(e) => setStrict(e.target.checked)} 
                  className="rounded text-indigo-500 bg-slate-900 border-slate-700 focus:ring-indigo-500"
                />
                <span className="text-slate-300 text-sm">Strict atom/bond type comparison</span>
              </label>

              <label className="flex items-center space-x-3 cursor-pointer">
                <input 
                  type="checkbox" 
                  checked={stereo} 
                  onChange={(e) => setStereo(e.target.checked)} 
                  className="rounded text-indigo-500 bg-slate-900 border-slate-700 focus:ring-indigo-500"
                />
                <span className="text-slate-300 text-sm">Check configuration (E/Z and R/S)</span>
              </label>
            </div>
          </div>

          <div className="pt-4">
            <button 
              type="submit" 
              disabled={searching || !searchQuery}
              className="w-full py-4 px-4 bg-gradient-to-r from-purple-600 to-pink-600 hover:from-purple-500 hover:to-pink-500 text-white font-bold rounded-xl shadow-lg disabled:opacity-50 transition-all text-lg"
            >
              {searching ? "Searching Database..." : "Execute Search"}
            </button>
          </div>

        </form>
      </div>
    </div>
  );
}
