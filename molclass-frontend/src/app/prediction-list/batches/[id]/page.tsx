import { redirect } from "next/navigation";

export default function LegacyBatchDetailRedirect() {
  redirect("/dataset-review");
}
