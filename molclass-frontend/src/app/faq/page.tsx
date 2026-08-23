export default function FaqPage() {
  return (
    <div className="max-w-4xl mx-auto mt-12 space-y-12">
      <header>
        <p className="font-mono text-xs uppercase tracking-[0.28em] text-blue-500">Common questions</p>
        <h1 className="mt-3 text-3xl font-bold tracking-tight text-foreground sm:text-5xl">FAQ</h1>
        <p className="mt-4 max-w-2xl text-sm leading-6 text-muted-foreground sm:text-base">Deployment, training, and integrating MolClass into your own workflows.</p>
      </header>

      <div className="bg-card/50 backdrop-blur-md rounded-2xl border border-border shadow-2xl p-8 space-y-8">

        <section className="space-y-4">
          <h2 className="text-2xl font-semibold text-foreground border-b border-border pb-2">How can I train a model?</h2>
          <div className="text-muted-foreground space-y-2 leading-relaxed">
            <p>Not on this server &mdash; this instance is configured to serve predictions and search against already-approved models only. Uploading new data and training new models are both disabled here.</p>
            <p>To train your own models, download the MolClass code and run it yourself (see the next question).</p>
          </div>
        </section>

        <section className="space-y-4">
          <h2 className="text-2xl font-semibold text-foreground border-b border-border pb-2">Where can I download MolClass?</h2>
          <div className="text-muted-foreground space-y-2 leading-relaxed">
            <p>MolClass is on GitHub:</p>
            <p>
              <a
                href="https://github.com/jwildenhain/molclass"
                target="_blank"
                rel="noopener noreferrer"
                className="text-indigo-400 hover:underline font-mono text-sm"
              >
                github.com/jwildenhain/molclass
              </a>
            </p>
          </div>
        </section>

        <section className="space-y-4">
          <h2 className="text-2xl font-semibold text-foreground border-b border-border pb-2">What&rsquo;s the best way to deploy it?</h2>
          <div className="text-muted-foreground space-y-3 leading-relaxed">
            <p>Docker Compose. The repository ships a complete stack &mdash; database, API, SDF import worker, model-training worker, prediction service, and frontend &mdash; defined in a single <code className="rounded bg-muted/50 px-1.5 py-0.5 font-mono text-sm">docker-compose.yml</code>.</p>
            <div className="bg-muted/50 p-6 rounded-xl border border-border font-mono text-xs sm:text-sm text-muted-foreground overflow-x-auto">
              <pre className="whitespace-pre">{`git clone https://github.com/jwildenhain/molclass.git
cd molclass

# Create an untracked .env with two required secrets
cat > .env <<'EOF'
MOLCLASS_DB_ROOT_PASSWORD=<a long random secret>
MOLCLASS_DB_PASSWORD=<a different long random secret>
EOF

docker compose build
docker compose up -d`}</pre>
            </div>
            <p>The frontend listens on <code className="rounded bg-muted/50 px-1.5 py-0.5 font-mono text-sm">127.0.0.1:3000</code> by default (override with <code className="rounded bg-muted/50 px-1.5 py-0.5 font-mono text-sm">MOLCLASS_FRONTEND_PORT</code> in <code className="rounded bg-muted/50 px-1.5 py-0.5 font-mono text-sm">.env</code>); put a reverse proxy in front of it for a public deployment. See <code className="rounded bg-muted/50 px-1.5 py-0.5 font-mono text-sm">docker-compose.yml</code> for the full set of tunables &mdash; per-service memory limits, ports, and worker thread counts.</p>
          </div>
        </section>

        <section className="space-y-4">
          <h2 className="text-2xl font-semibold text-foreground border-b border-border pb-2">How can I use it with my own workflows?</h2>
          <div className="text-muted-foreground space-y-2 leading-relaxed">
            <p>MolClass ships an MCP (Model Context Protocol) server, so AI assistants and agent workflows (Claude Desktop, Claude Code, or any MCP-compatible client) can look up compounds and run predictions directly against a MolClass deployment.</p>
            <p>It&rsquo;s in the repository under <code className="rounded bg-muted/50 px-1.5 py-0.5 font-mono text-sm">mcp-server/</code>, with setup instructions in its README. To keep any one deployment from being overwhelmed by automated requests, each MCP server instance self-limits to <strong className="text-foreground">100 predictions per day</strong>.</p>
          </div>
        </section>

      </div>
    </div>
  );
}
