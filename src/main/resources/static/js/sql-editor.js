(() => {
  const keywords = new Set((
    'ALL AND AS ASC BETWEEN BIGINT BOOLEAN BOTH BY CASE CAST CHAR CROSS CURRENT_DATE CURRENT_TIMESTAMP ' +
    'DATE DATETIME DECIMAL DESC DISTINCT DOUBLE ELSE END EXCEPT EXISTS FALSE FIRST FLOAT FOLLOWING ' +
    'FROM FULL GROUP HAVING IF IN INNER INSERT INT INTEGER INTERSECT INTERVAL INTO IS JOIN KEY ' +
    'LAST LEFT LIKE LIMIT NOT NULL NULLS OFFSET ON OR ORDER OUTER OVER PARTITION PRECEDING RANGE ' +
    'REGEXP RIGHT ROW ROWS SELECT SMALLINT STRING THEN TIMESTAMP TINYINT TRUE UNBOUNDED UNION ' +
    'UNIQUE USING VALUES VARCHAR WHEN WHERE WITH'
  ).split(' '));
  const functions = new Set((
    'ABS AVG BITMAP_UNION COALESCE CONCAT CONCAT_WS COUNT CURDATE DATE_ADD DATE_FORMAT DATE_SUB ' +
    'DATE_TRUNC DATEDIFF DENSE_RANK FLOOR FROM_UNIXTIME HLL_UNION IFNULL JSON_EXTRACT LAG LEAD ' +
    'LOWER MAX MIN NOW NULLIF RANK REPLACE ROUND ROW_NUMBER SPLIT_PART STR_TO_DATE SUBSTR ' +
    'SUBSTRING SUM TO_DATE TRIM UNIX_TIMESTAMP UPPER YEAR'
  ).split(' '));
  const tokens = /(?<comment>--[^\r\n]*|#[^\r\n]*|\/\*[\s\S]*?(?:\*\/|$))|(?<string>'(?:''|\\[\s\S]|[^'\\])*(?:'|$)|"(?:""|\\[\s\S]|[^"\\])*(?:"|$))|(?<identifier>`(?:``|\\[\s\S]|[^`\\])*(?:`|$))|(?<word>[\p{L}_$][\p{L}\p{N}_$]*)|(?<number>\b0x[\da-f]+\b|(?:\b\d+(?:\.\d*)?|\.\d+)(?:e[+-]?\d+)?\b)|(?<operator><=>|>=|<=|<>|!=|\|\||[-+*/%=<>!&|^~])/giu;

  function highlight(sql) {
    const result = document.createDocumentFragment();
    let end = 0;
    for (const match of sql.matchAll(tokens)) {
      result.append(document.createTextNode(sql.slice(end, match.index)));
      let type = Object.keys(match.groups).find(key => match.groups[key] !== undefined);
      if (type === 'word') {
        const word = match[0].toUpperCase();
        type = keywords.has(word) ? 'keyword' : functions.has(word) ? 'function' : null;
      }
      if (type) {
        const span = document.createElement('span');
        span.className = `sql-token-${type}`;
        span.textContent = match[0];
        result.append(span);
      } else {
        result.append(document.createTextNode(match[0]));
      }
      end = match.index + match[0].length;
    }
    // Keep the final empty line measurable, just as it is in the textarea.
    result.append(document.createTextNode(sql.slice(end) + '\n'));
    return result;
  }

  document.querySelectorAll('textarea[data-sql-editor]').forEach(textarea => {
    const editor = document.createElement('div');
    editor.className = 'sql-editor';
    const preview = document.createElement('pre');
    preview.className = 'sql-editor-highlight';
    preview.setAttribute('aria-hidden', 'true');
    textarea.before(editor);
    editor.append(preview, textarea);

    function syncScroll() {
      preview.scrollTop = textarea.scrollTop;
      preview.scrollLeft = textarea.scrollLeft;
    }
    function syncSize() {
      // Exclude native scrollbars so both layers wrap at the same column.
      preview.style.width = `${textarea.clientWidth}px`;
      preview.style.height = `${textarea.clientHeight}px`;
      syncScroll();
    }
    function render() {
      preview.replaceChildren(highlight(textarea.value));
      syncSize();
    }

    render();
    textarea.classList.add('sql-editor-input');
    textarea.addEventListener('input', render);
    textarea.addEventListener('scroll', syncScroll);
    window.addEventListener('pageshow', render);
    if (textarea.form) textarea.form.addEventListener('reset', () => requestAnimationFrame(render));
    if (window.ResizeObserver) new ResizeObserver(syncSize).observe(textarea);
    else window.addEventListener('resize', syncSize);
  });
})();
