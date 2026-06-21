import Link from "next/link";

export default function Home() {
  return (
    <div className="flex flex-col items-center justify-center min-h-[70vh] space-y-12 text-center pb-12">
      
      {/* Hero Section */}
      <div className="space-y-6 max-w-3xl">
        <h1 className="text-5xl md:text-7xl font-extrabold tracking-tight text-transparent bg-clip-text bg-gradient-to-br from-blue-400 via-indigo-400 to-emerald-400 animate-pulse-slow">
          MolClass V2
        </h1>
        <p className="text-lg md:text-xl text-slate-400 font-light leading-relaxed">
          High-throughput screening bioactivity prediction. 
          A fully modernized pipeline powered by advanced Machine Learning ensembles.
        </p>
      </div>

      {/* Action Cards */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-8 w-full max-w-6xl z-10 px-4">
        
        {/* Upload Card */}
        <Link href="/upload" className="group relative p-1 rounded-2xl bg-gradient-to-br from-blue-500/30 to-indigo-500/30 hover:from-blue-500/50 hover:to-indigo-500/50 transition-all duration-300 shadow-xl shadow-blue-900/20">
          <div className="bg-slate-900/80 backdrop-blur-sm p-8 rounded-xl h-full border border-slate-800/50 flex flex-col justify-between group-hover:bg-slate-900/60 transition-colors">
            <div>
              <div className="w-12 h-12 rounded-lg bg-blue-500/20 flex items-center justify-center mb-6 border border-blue-500/30">
                <svg className="w-6 h-6 text-blue-400" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-8l-4-4m0 0L8 8m4-4v12"></path></svg>
              </div>
              <h2 className="text-2xl font-bold text-slate-100 mb-3">Upload SDF</h2>
              <p className="text-slate-400">Ingest new compound datasets directly into the unified Molclass database via drag-and-drop.</p>
            </div>
            <div className="mt-6 text-blue-400 font-medium group-hover:translate-x-1 transition-transform flex items-center">Get Started &rarr;</div>
          </div>
        </Link>

        {/* Model Creation Card */}
        <Link href="/model-creation" className="group relative p-1 rounded-2xl bg-gradient-to-br from-indigo-500/30 to-purple-500/30 hover:from-indigo-500/50 hover:to-purple-500/50 transition-all duration-300 shadow-xl shadow-indigo-900/20">
          <div className="bg-slate-900/80 backdrop-blur-sm p-8 rounded-xl h-full border border-slate-800/50 flex flex-col justify-between group-hover:bg-slate-900/60 transition-colors">
            <div>
              <div className="w-12 h-12 rounded-lg bg-indigo-500/20 flex items-center justify-center mb-6 border border-indigo-500/30">
                <svg className="w-6 h-6 text-indigo-400" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M19.428 15.428a2 2 0 00-1.022-.547l-2.387-.477a6 6 0 00-3.86.517l-.318.158a6 6 0 01-3.86.517L6.05 15.21a2 2 0 00-1.806.547M8 4h8l-1 1v5.172a2 2 0 00.586 1.414l5 5c1.26 1.26.367 3.414-1.415 3.414H4.828c-1.782 0-2.674-2.154-1.414-3.414l5-5A2 2 0 009 10.172V5L8 4z"></path></svg>
              </div>
              <h2 className="text-2xl font-bold text-slate-100 mb-3">Create Models</h2>
              <p className="text-slate-400">Configure and queue advanced machine learning models (RandomForest, J48, etc.) against specific datasets.</p>
            </div>
            <div className="mt-6 text-indigo-400 font-medium group-hover:translate-x-1 transition-transform flex items-center">Configure Jobs &rarr;</div>
          </div>
        </Link>

        {/* Structure Search Card */}
        <Link href="/structure-search" className="group relative p-1 rounded-2xl bg-gradient-to-br from-purple-500/30 to-pink-500/30 hover:from-purple-500/50 hover:to-pink-500/50 transition-all duration-300 shadow-xl shadow-purple-900/20">
          <div className="bg-slate-900/80 backdrop-blur-sm p-8 rounded-xl h-full border border-slate-800/50 flex flex-col justify-between group-hover:bg-slate-900/60 transition-colors">
            <div>
              <div className="w-12 h-12 rounded-lg bg-purple-500/20 flex items-center justify-center mb-6 border border-purple-500/30">
                <svg className="w-6 h-6 text-purple-400" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"></path></svg>
              </div>
              <h2 className="text-2xl font-bold text-slate-100 mb-3">Structure Search</h2>
              <p className="text-slate-400">Search the database using SMILES or InChI for exact, substructure, and Tanimoto similarity matches.</p>
            </div>
            <div className="mt-6 text-purple-400 font-medium group-hover:translate-x-1 transition-transform flex items-center">Execute Search &rarr;</div>
          </div>
        </Link>

        {/* Prediction List Card */}
        <Link href="/prediction-list" className="group relative p-1 rounded-2xl bg-gradient-to-br from-emerald-500/30 to-teal-500/30 hover:from-emerald-500/50 hover:to-teal-500/50 transition-all duration-300 shadow-xl shadow-emerald-900/20">
          <div className="bg-slate-900/80 backdrop-blur-sm p-8 rounded-xl h-full border border-slate-800/50 flex flex-col justify-between group-hover:bg-slate-900/60 transition-colors">
            <div>
              <div className="w-12 h-12 rounded-lg bg-emerald-500/20 flex items-center justify-center mb-6 border border-emerald-500/30">
                <svg className="w-6 h-6 text-emerald-400" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-6 9l2 2 4-4"></path></svg>
              </div>
              <h2 className="text-2xl font-bold text-slate-100 mb-3">Predictions</h2>
              <p className="text-slate-400">Monitor model training progress and view predictions applied to compound sets.</p>
            </div>
            <div className="mt-6 text-emerald-400 font-medium group-hover:translate-x-1 transition-transform flex items-center">View Results &rarr;</div>
          </div>
        </Link>

        {/* Dataset Review Card */}
        <Link href="/dataset-review" className="group relative p-1 rounded-2xl bg-gradient-to-br from-cyan-500/30 to-blue-500/30 hover:from-cyan-500/50 hover:to-blue-500/50 transition-all duration-300 shadow-xl shadow-cyan-900/20">
          <div className="bg-slate-900/80 backdrop-blur-sm p-8 rounded-xl h-full border border-slate-800/50 flex flex-col justify-between group-hover:bg-slate-900/60 transition-colors">
            <div>
              <div className="w-12 h-12 rounded-lg bg-cyan-500/20 flex items-center justify-center mb-6 border border-cyan-500/30">
                <svg className="w-6 h-6 text-cyan-400" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M12 6.253v13m0-13C10.832 5.477 9.246 5 7.5 5S4.168 5.477 3 6.253v13C4.168 18.477 5.754 18 7.5 18s3.332.477 4.5 1.253m0-13C13.168 5.477 14.754 5 16.5 5c1.747 0 3.332.477 4.5 1.253v13C19.832 18.477 18.247 18 16.5 18c-1.746 0-3.332.477-4.5 1.253"></path></svg>
              </div>
              <h2 className="text-2xl font-bold text-slate-100 mb-3">Dataset Review</h2>
              <p className="text-slate-400">Review detailed phenotypic and biochemical descriptions of MolClass datasets.</p>
            </div>
            <div className="mt-6 text-cyan-400 font-medium group-hover:translate-x-1 transition-transform flex items-center">Explore Datasets &rarr;</div>
          </div>
        </Link>

        {/* Details Card */}
        <Link href="/details" className="group relative p-1 rounded-2xl bg-gradient-to-br from-amber-500/30 to-orange-500/30 hover:from-amber-500/50 hover:to-orange-500/50 transition-all duration-300 shadow-xl shadow-amber-900/20">
          <div className="absolute inset-0 bg-slate-900/80 backdrop-blur-sm rounded-2xl"></div>
          <div className="relative p-6 flex flex-col items-center text-center space-y-4 rounded-xl h-full justify-center">
            <div className="p-4 bg-amber-500/20 rounded-full group-hover:scale-110 transition-transform duration-300">
              <svg className="w-8 h-8 text-amber-400" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" /></svg>
            </div>
            <h2 className="text-xl font-bold text-slate-200">Details & Citations</h2>
            <p className="text-sm text-slate-400">About MolClass, contact information, and academic citations.</p>
          </div>
        </Link>

      </div>
    </div>
  );
}
