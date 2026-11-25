const paths = {
  folder: 'M3 6a2 2 0 0 1 2-2h5l2 3h7a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z',
  pin: 'm15 3 6 6-3 1-4 4 .5 3.5-3-1L5 22l-1-1 5.5-6.5-2-3 3.5.5 4-5Z',
  add: 'M12 5v14M5 12h14', menu: 'M12 5h.01M12 12h.01M12 19h.01',
}
export const SiderIcon = ({ name }: { name: keyof typeof paths }) => <svg aria-hidden="true" width="18" height="18"
  viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={name === 'menu' ? 3.6 : 1.7}
  strokeLinecap="round" strokeLinejoin="round"><path d={paths[name]} /></svg>
