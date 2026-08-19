import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  output: "standalone",
  async rewrites() {
    return [
      {
        source: "/api/v3/:path*",
        destination: process.env.PREDICTOR_API_URL
          ? `${process.env.PREDICTOR_API_URL}/api/v3/:path*`
          : "http://localhost:8082/api/v3/:path*",
      },

      {
        source: "/api/:path*",
        destination: process.env.API_URL 
          ? `${process.env.API_URL}/api/:path*` 
          : "http://localhost:8000/api/:path*", // Default for local dev (matches run.sh / .env.example)
      },
      {
        source: "/predict/:path*",
        destination: process.env.API_URL 
          ? `${process.env.API_URL}/predict/:path*` 
          : "http://localhost:8000/predict/:path*",
      },
    ];
  },
};

export default nextConfig;
