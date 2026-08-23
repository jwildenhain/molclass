import { redirect } from "next/navigation";

export default function LegacyModelDetailRedirect() {
  redirect("/search?tab=models");
}
