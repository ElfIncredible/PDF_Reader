from app import rag


class FakeLLM:
    """Records prompts and returns canned replies."""

    def __init__(self, reply):
        self.reply = reply
        self.calls = []

    def complete(self, system, prompt, max_tokens=900):
        self.calls.append(prompt)
        return self.reply(prompt) if callable(self.reply) else self.reply


PAGES = [
    "Tenancy agreement between Amina Otieno and Baraka Properties Ltd for a flat in Kilimani, Nairobi.",
    "Rent is KES 45,000 per month, payable on or before the 5th day of each month.",
    "The deposit of KES 90,000 is refundable within 30 days after the tenant moves out.",
    "Either party may end the agreement by giving two months' written notice.",
]


def test_chunks_keep_page_numbers_and_overlap():
    long_page = " ".join(f"word{i}" for i in range(600))
    chunks = rag.chunk_pages(["", long_page, "short page"], size=500, overlap=100)
    assert {c.page for c in chunks} == {2, 3}
    assert all(len(c.text) <= 500 for c in chunks)
    first, second = chunks[0].text.split(), chunks[1].text.split()
    assert set(first[-5:]) & set(second[:30]), "consecutive chunks should overlap"


def test_bm25_finds_the_right_page():
    index = rag.BM25(rag.chunk_pages(PAGES))
    best = index.search("how much is the deposit refund", k=1)[0][0]
    assert best.page == 3
    assert index.search("notice to end the agreement", k=1)[0][0].page == 4


def test_cited_pages_parsing():
    assert rag.cited_pages("Rent is due [p. 2]. Deposit [p.3] and again [p. 2], see [p. 3, 4].") == [2, 3, 4]


def test_ask_returns_answer_with_citations_from_retrieved_pages_only():
    llm = FakeLLM("The deposit is KES 90,000, refunded within 30 days [p. 3]. Also [p. 9].")
    out = rag.ask(llm, PAGES, "When is the deposit refunded?")
    assert out["answer"].startswith("The deposit")
    assert [c["page"] for c in out["citations"]] == [3]  # p. 9 wasn't retrieved, so it's dropped
    assert "[p. 3]" in llm.calls[0]  # the excerpt was given to the model


def test_ask_rejects_documents_without_text():
    try:
        rag.ask(FakeLLM("x"), ["", "  "], "anything?")
    except ValueError as e:
        assert "no text" in str(e)
    else:
        raise AssertionError("expected ValueError")


def test_parse_summary_lenient():
    s, pts = rag.parse_summary("SUMMARY:\nA lease for a flat.\nKEY POINTS:\n- Rent KES 45,000 [p. 2]\n2) Notice two months [p. 4]\n")
    assert s == "A lease for a flat."
    assert pts == ["Rent KES 45,000 [p. 2]", "Notice two months [p. 4]"]
    s2, pts2 = rag.parse_summary("Just a paragraph with no headings.")
    assert s2 == "Just a paragraph with no headings." and pts2 == []


def test_summarize_single_call_for_short_documents():
    llm = FakeLLM("SUMMARY:\nA tenancy agreement.\nKEY POINTS:\n- Rent is KES 45,000 [p. 2]")
    out = rag.summarize(llm, PAGES, "short", "Lease", max_chars=10_000)
    assert len(llm.calls) == 1 and out["parts"] == 1
    assert out["points"] == ["Rent is KES 45,000 [p. 2]"]
    assert "[Page 2]" in llm.calls[0]


def test_summarize_map_reduce_for_long_documents():
    pages = [f"Section {i}. " + "Important detail. " * 60 for i in range(1, 21)]
    llm = FakeLLM(lambda p: "SUMMARY:\nCombined.\nKEY POINTS:\n- A [p. 1]" if "Combine them" in p else "notes [p. 1]")
    out = rag.summarize(llm, pages, "medium", None, max_chars=3000)
    assert out["parts"] > 1
    assert len(llm.calls) == out["parts"] + 1  # one per part, plus the final combine
    assert out["summary"] == "Combined."
