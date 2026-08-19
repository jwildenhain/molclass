import type { Metadata } from "next";
import { Inter } from "next/font/google";
import "./globals.css";
import Link from "next/link";
import { ThemeProvider } from "@/components/ThemeProvider";
import { ThemeToggle } from "@/components/ThemeToggle";

const inter = Inter({ subsets: ["latin"] });

export const metadata: Metadata = {
  title: "MolClass V2",
  description: "Next-Generation Machine Learning Bioactivity Predictor",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en" suppressHydrationWarning>
      <body className={`${inter.className} bg-background text-foreground min-h-screen flex flex-col transition-colors duration-300`}>
        <ThemeProvider attribute="class" defaultTheme="system" enableSystem>
          {/* Navigation Bar */}
          <nav className="fixed top-0 w-full z-50 bg-background/80 backdrop-blur-md border-b border-border shadow-sm">
            <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
              <div className="flex items-center justify-between h-16">
                <div className="flex items-center space-x-8">
                  <Link href="/" className="text-2xl font-bold bg-clip-text text-transparent bg-gradient-to-r from-primary to-emerald-400">
                    MolClass V2
                  </Link>
                  <div className="hidden md:block">
                    <div className="flex items-baseline space-x-4">
                      <Link href="/upload" className="text-muted-foreground hover:text-foreground hover:bg-muted px-3 py-2 rounded-md text-sm font-medium transition-colors">Upload</Link>
                      <Link href="/model-creation" className="text-muted-foreground hover:text-foreground hover:bg-muted px-3 py-2 rounded-md text-sm font-medium transition-colors">Model Creation</Link>
                      <Link href="/structure-search" className="text-muted-foreground hover:text-foreground hover:bg-muted px-3 py-2 rounded-md text-sm font-medium transition-colors">Structure Search</Link>
                      <Link href="/model-review" className="text-muted-foreground hover:text-foreground hover:bg-muted px-3 py-2 rounded-md text-sm font-medium transition-colors">Model Review</Link>
                      <Link href="/prediction-list" className="text-muted-foreground hover:text-foreground hover:bg-muted px-3 py-2 rounded-md text-sm font-medium transition-colors">Prediction List</Link>
                      <Link href="/details" className="text-muted-foreground hover:text-foreground hover:bg-muted px-3 py-2 rounded-md text-sm font-medium transition-colors">Details</Link>
                    </div>
                  </div>
                </div>
                <div className="flex items-center gap-4">
                  <details className="relative md:hidden">
                    <summary className="list-none cursor-pointer rounded-md px-3 py-2 text-sm font-medium text-muted-foreground transition-colors hover:bg-muted hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring [&::-webkit-details-marker]:hidden">
                      Menu
                    </summary>
                    <ul className="absolute right-0 mt-2 w-52 space-y-1 rounded-md border border-border bg-background p-2 shadow-lg">
                      <li>
                        <Link href="/structure-search" className="block rounded-md px-3 py-2 text-sm font-medium text-muted-foreground transition-colors hover:bg-muted hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
                          Structure Search
                        </Link>
                      </li>
                      <li>
                        <Link href="/model-review" className="block rounded-md px-3 py-2 text-sm font-medium text-muted-foreground transition-colors hover:bg-muted hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
                          Model Review
                        </Link>
                      </li>
                    </ul>
                  </details>
                  <ThemeToggle />
                </div>
              </div>
            </div>
          </nav>

          {/* Main Content Area */}
          <main className="flex-grow pt-24 px-4 sm:px-6 lg:px-8 max-w-7xl mx-auto w-full">
            {children}
          </main>
          
          {/* Footer */}
          <footer className="py-6 text-center text-muted-foreground text-sm border-t border-border mt-12 bg-muted/30">
            &copy; {new Date().getFullYear()} MolClass Project. Modernized Interface.
          </footer>
        </ThemeProvider>
      </body>
    </html>
  );
}
