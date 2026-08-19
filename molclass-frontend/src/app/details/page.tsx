export default function DetailsPage() {
  return (
    <div className="max-w-4xl mx-auto mt-12 space-y-12">
      <div className="text-center space-y-4">
        <h1 className="text-4xl font-bold text-foreground">About MolClass</h1>
        <p className="text-xl text-muted-foreground">High-throughput bioactivity prediction via modern machine learning ensembles.</p>
      </div>

      <div className="bg-card/50 backdrop-blur-md rounded-2xl border border-border shadow-2xl p-8 space-y-8">
        
        <section className="space-y-4">
          <h2 className="text-2xl font-semibold text-foreground border-b border-border pb-2">How to Cite</h2>
          <div className="bg-muted/50 p-6 rounded-xl border border-border font-mono text-sm text-muted-foreground">
            <p className="mb-2">If you use this software for your work, please cite:</p>
            <p className="text-indigo-400">Bioinformatics. 2012 Aug 15;28(16):2200-1 Wildenhain J, Fitzgerald N, Tyers M.</p>
          </div>
        </section>

        <section className="space-y-4">
          <h2 className="text-2xl font-semibold text-foreground border-b border-border pb-2">Version History (V2)</h2>
          <div className="text-muted-foreground space-y-2 leading-relaxed">
            <p>MolClass V2 replaces the original monolithic PHP architecture with a completely modernized, decoupled Next.js + React frontend running on TailwindCSS.</p>
            <p>The backend pipeline is powered by a high-performance Spring Boot API utilizing multi-threaded Weka prediction algorithms. The core database has been unified (Molclass V1.5 -&gt; V2) to support over 115 trained models.</p>
            <p>Legacy features such as Klekota-Roth fingerprints and preclustering by Murcko-Fragments remain intact, seamlessly integrated into the new architecture.</p>
          </div>
        </section>

        <section className="space-y-4">
          <h2 className="text-2xl font-semibold text-foreground border-b border-border pb-2">Contact Information</h2>
          <div className="bg-muted/30 p-6 rounded-xl border border-border/50 flex flex-col md:flex-row justify-between items-start md:items-center">
            <div className="text-muted-foreground">
              <p className="font-bold text-foreground">Jan Wildenhain</p>
              <p>School of Biological Sciences</p>
              <p>Darwin Building, Room 303</p>
              <p>University of Edinburgh Mayfield Road</p>
              <p>Edinburgh EH9 3JR, Scotland, UK</p>
            </div>
            <div className="mt-6 md:mt-0">
              <a href="mailto:jan.wildenhain@ed.ac.uk" className="px-6 py-3 bg-slate-700 hover:bg-slate-600 text-white font-medium rounded-lg transition-colors inline-block">
                Contact via Email
              </a>
            </div>
          </div>
          <p className="text-sm text-slate-500 italic mt-4">
            If you did not get a response to an email please do not hesitate to resend your email. We are very much interested in your feedback.
          </p>
        </section>

      </div>
    </div>
  );
}
