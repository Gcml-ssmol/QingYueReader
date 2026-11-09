const paths={
 library:'M3 4h6l3 2 3-2h6v15h-6l-3 2-3-2H3z M12 6v15',
 search:'M10.5 18a7.5 7.5 0 1 0 0-15 7.5 7.5 0 0 0 0 15z M16 16l5 5',
 trash:'M3 6h18 M9 6V3h6v3 M5 6l1 15h12l1-15 M10 10v7 M14 10v7',
 settings:'M10 3h4l.6 2.3 2 .9 2.1-.7 2 3.5-1.6 1.6v2.8l1.6 1.6-2 3.5-2.1-.7-2 .9L14 21h-4l-.6-2.3-2-.9-2.1.7-2-3.5 1.6-1.6v-2.8L3.3 9l2-3.5 2.1.7 2-.9z M15 12a3 3 0 1 0-6 0 3 3 0 0 0 6 0',
 upload:'M12 15V3 M8 7l4-4 4 4 M5 12H3v9h18v-9h-2',
 plus:'M12 5v14 M5 12h14',
 arrow:'M4 12h16 M14 6l6 6-6 6',
 browse:'M8 12V5a1.5 1.5 0 0 1 3 0v6-8a1.5 1.5 0 0 1 3 0v8-6a1.5 1.5 0 0 1 3 0v7-4a1.5 1.5 0 0 1 3 0v7c0 4-3 7-6 7-4 0-5-2-7-5l-3-4c-1-2 1-3 2-2z',
 pen:'M4 20l1-6L16 3a2 2 0 0 1 3 0l2 2a2 2 0 0 1 0 3L10 19z M14 5l5 5 M5 14l5 5 M3 22h9',
 highlight:'M4 15l9-11 7 6-10 11z M12 6l6 5 M4 15l6 6 M4 19l-2 3h6',
 eraser:'M3 14l9-11a2 2 0 0 1 3 0l6 5a2 2 0 0 1 0 3l-8 10H9l-6-5z M7 9l10 9 M13 21h9',
 text:'M4 5h16 M12 5v15 M8 20h8 M4 5v3 M20 5v3',
 undo:'M8 4L3 9l5 5 M3 9h10a7 7 0 0 1 0 14',
 file:'M5 2h10l4 4v16H5z M14 2v5h5 M8 11h8 M8 15h8 M8 19h5',
 note:'M5 2h14v20H5z M8 6h8 M8 10h5 M8 14h8 M8 18h5',
 sun:'M12 7a5 5 0 1 0 0 10 5 5 0 0 0 0-10z M12 1v3 M12 20v3 M1 12h3 M20 12h3 M4 4l2 2 M18 18l2 2 M20 4l-2 2 M6 18l-2 2',
 moon:'M20 15A9 9 0 0 1 9 4a9 9 0 1 0 11 11z',
 system:'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z M12 3v18',
 back:'M15 5l-7 7 7 7',
 check:'M5 12l4 4L19 6'
};
export function icon(name,className='ui-icon'){
 const svg=document.createElementNS('http://www.w3.org/2000/svg','svg');svg.setAttribute('viewBox','0 0 24 24');svg.setAttribute('fill','none');svg.setAttribute('stroke','currentColor');svg.setAttribute('stroke-width','1.65');svg.setAttribute('stroke-linecap','round');svg.setAttribute('stroke-linejoin','round');svg.setAttribute('aria-hidden','true');svg.classList.add(className);
 const path=document.createElementNS(svg.namespaceURI,'path');path.setAttribute('d',paths[name]||paths.file);svg.append(path);return svg;
}
